package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private final TransferProbe probe;
  private final int retries, lockMillis, statementMillis;

  @Autowired
  public PaymentService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      MeterRegistry metrics,
      Optional<TransferProbe> probe,
      @Value("${payments.db-retries:3}") int retries,
      @Value("${payments.lock-timeout-ms:2000}") int lockMillis,
      @Value("${payments.statement-timeout-ms:5000}") int statementMillis) {
    this(db, manager, metrics, probe.orElse(() -> {}), retries, lockMillis, statementMillis);
  }

  public PaymentService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      MeterRegistry metrics,
      TransferProbe probe,
      int retries,
      int lockMillis,
      int statementMillis) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    tx.setTimeout(15);
    this.metrics = metrics;
    this.probe = probe;
    this.retries = retries;
    this.lockMillis = lockMillis;
    this.statementMillis = statementMillis;
  }

  public PaymentOutcome transfer(PaymentCommand c, Caller caller, String correlation) {
    if (!caller.has("payments:write"))
      throw new ApiFailure(403, "FORBIDDEN", "The operation is not permitted.");
    long start = System.nanoTime();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          PaymentOutcome outcome =
              Objects.requireNonNull(tx.execute(s -> execute(c, caller, correlation)));
          safeMetric(
              () -> {
                metrics
                    .counter(
                        "payments.outcomes",
                        "code",
                        String.valueOf(
                            outcome.body().getOrDefault("code", outcome.body().get("status"))))
                    .increment();
                if (outcome.replay()) metrics.counter("payments.replays").increment();
                else if ("COMPLETED".equals(outcome.body().get("status")))
                  metrics.counter("payments.completed").increment();
                else if ("REJECTED".equals(outcome.body().get("status")))
                  metrics
                      .counter(
                          "payments.rejected", "code", String.valueOf(outcome.body().get("code")))
                      .increment();
              });
          return outcome;
        } catch (DataAccessException e) {
          if (!retryable(e)) throw e;
          safeMetric(
              () -> {
                metrics.counter("payments.db.transient_failures").increment();
                if (hasSqlState(e, "55P03"))
                  metrics.counter("payments.db.lock_timeouts").increment();
              });
          if (attempt >= retries - 1)
            throw new ApiFailure(
                503,
                "TEMPORARILY_UNAVAILABLE",
                "Processing outcome may be queried or retried with the same paymentId.",
                c.paymentId().toString(),
                List.of());
          safeMetric(() -> metrics.counter("payments.retries").increment());
          try {
            Thread.sleep(10L * (attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(
                503, "TEMPORARILY_UNAVAILABLE", "Please retry with the same paymentId.");
          }
        } catch (org.springframework.transaction.TransactionException e) {
          throw new ApiFailure(
              503,
              "TEMPORARILY_UNAVAILABLE",
              "Processing outcome may be queried or retried with the same paymentId.",
              c.paymentId().toString(),
              List.of());
        }
      }
    } finally {
      safeMetric(
          () ->
              metrics
                  .timer("payments.processing")
                  .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS));
    }
  }

  private boolean hasSqlState(Throwable failure, String state) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof java.sql.SQLException sql && state.equals(sql.getSQLState()))
        return true;
    return false;
  }

  private boolean retryable(DataAccessException failure) {
    if (failure instanceof TransientDataAccessException
        || failure instanceof DataAccessResourceFailureException) return true;
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && (state.startsWith("08")
                || Set.of("40001", "40P01", "55P03", "57014").contains(state))) return true;
      }
    return false;
  }

  private void safeMetric(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      /* Telemetry never changes financial outcomes. */
    }
  }

  private PaymentOutcome execute(PaymentCommand c, Caller caller, String correlation) {
    db.queryForObject("SELECT set_config('lock_timeout',?,true)", String.class, lockMillis + "ms");
    db.queryForObject(
        "SELECT set_config('statement_timeout',?,true)", String.class, statementMillis + "ms");
    int inserted =
        db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES(?,?,?,?,?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO NOTHING",
            c.paymentId(),
            c.fromAccount(),
            c.toAccount(),
            c.amount(),
            Timestamp.from(c.date()),
            c.purpose(),
            c.notes(),
            c.fingerprint(),
            caller.subject(),
            correlation);
    if (inserted == 0) {
      Map<String, Object> p = payment(c.paymentId());
      if (!caller.subject().equals(p.get("requested_by")))
        throw new ApiFailure(403, "FORBIDDEN", "The operation is not permitted.");
      if (!c.fingerprint().equals(p.get("payload_fingerprint"))) return conflict(c, correlation);
      return stored(p, true, false);
    }
    List<Map<String, Object>> locked =
        db.queryForList(
            "SELECT a.*,c.status AS customer_status FROM accounts a JOIN customers c ON"
                + " c.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a",
            c.fromAccount(),
            c.toAccount());
    Map<String, Map<String, Object>> accounts = new HashMap<>();
    for (var a : locked) accounts.put((String) a.get("id"), a);
    var from = accounts.get(c.fromAccount());
    var to = accounts.get(c.toAccount());
    String code = null;
    int http = 409;
    if (from == null || !authorized(from, caller)) {
      code = caller.admin() && from == null ? "ACCOUNT_NOT_FOUND" : "FORBIDDEN";
      http = code.equals("FORBIDDEN") ? 403 : 404;
    } else if (to == null) {
      code = caller.admin() ? "ACCOUNT_NOT_FOUND" : "FORBIDDEN";
      http = caller.admin() ? 404 : 403;
    } else if (!Boolean.TRUE.equals(to.get("can_receive"))) {
      code = "FORBIDDEN";
      http = 403;
    } else if (!"ACTIVE".equals(from.get("status"))
        || !"ACTIVE".equals(to.get("status"))
        || !"ACTIVE".equals(from.get("customer_status"))
        || !"ACTIVE".equals(to.get("customer_status"))
        || !"JOD".equals(from.get("currency"))
        || !"JOD".equals(to.get("currency"))) {
      code = "ACCOUNT_NOT_ACTIVE";
    } else if (c.amount().compareTo((BigDecimal) from.get("max_transfer")) > 0
        || c.amount().compareTo((BigDecimal) to.get("max_transfer")) > 0) {
      code = "PAYMENT_LIMIT_EXCEEDED";
    } else if (((BigDecimal) from.get("available_balance")).compareTo(c.amount()) < 0) {
      code = "INSUFFICIENT_FUNDS";
    }
    if (code != null) {
      db.update(
          "UPDATE payments SET"
              + " status='REJECTED',rejection_code=?,rejection_http=?,processed_at=now() WHERE"
              + " payment_id=?",
          code,
          http,
          c.paymentId());
      return stored(payment(c.paymentId()), false, false);
    }
    BigDecimal debit = ((BigDecimal) from.get("available_balance")).subtract(c.amount()),
        credit = ((BigDecimal) to.get("available_balance")).add(c.amount());
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        debit,
        c.fromAccount());
    probe.afterDebit();
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        credit,
        c.toAccount());
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
        c.paymentId(),
        c.fromAccount(),
        c.amount().negate(),
        debit,
        c.paymentId(),
        c.toAccount(),
        c.amount(),
        credit);
    db.update(
        "UPDATE payments SET status='COMPLETED',processed_at=now() WHERE payment_id=?",
        c.paymentId());
    return stored(payment(c.paymentId()), false, false);
  }

  private PaymentOutcome conflict(PaymentCommand c, String cid) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("paymentId", c.paymentId().toString());
    e.put("status", "ERROR");
    e.put("code", "IDEMPOTENCY_CONFLICT");
    e.put("message", "The payment ID identifies a different instruction.");
    e.put("issues", List.of());
    e.put("correlationId", cid);
    e.put("timestamp", Instant.now().toString());
    return new PaymentOutcome(409, e, false);
  }

  private boolean authorized(Map<String, Object> account, Caller caller) {
    return caller.admin()
        || Objects.equals(caller.customerId(), account.get("customer_id"))
        || Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM account_debit_grants WHERE account_id=? AND"
                    + " subject=?)",
                Boolean.class,
                account.get("id"),
                caller.subject()));
  }

  private Map<String, Object> payment(UUID id) {
    return db.queryForMap("SELECT * FROM payments WHERE payment_id=?", id);
  }

  private PaymentOutcome stored(Map<String, Object> p, boolean replay, boolean lookup) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paymentId", p.get("payment_id").toString());
    body.put("status", p.get("status"));
    if ("COMPLETED".equals(p.get("status"))) {
      body.put("fromAccount", p.get("from_account_id"));
      body.put("toAccount", p.get("to_account_id"));
      body.put("amount", ((BigDecimal) p.get("amount")).setScale(3).toPlainString());
      body.put("currency", "JOD");
      body.put("processedAt", ((Timestamp) p.get("processed_at")).toInstant().toString());
      return new PaymentOutcome(lookup || replay ? 200 : 201, body, replay);
    }
    String code = (String) p.get("rejection_code");
    body.put("code", code);
    body.put("message", "The payment could not be completed.");
    body.put(
        "issues",
        code.equals("INSUFFICIENT_FUNDS")
            ? List.of(new Issue("amount", code, "Available funds are insufficient."))
            : List.of());
    body.put("correlationId", p.get("correlation_id"));
    body.put("timestamp", ((Timestamp) p.get("processed_at")).toInstant().toString());
    return new PaymentOutcome(
        lookup ? 200 : ((Number) p.get("rejection_http")).intValue(), body, replay);
  }

  public PaymentOutcome get(UUID id, Caller caller) {
    if (!caller.has("payments:read"))
      throw new ApiFailure(403, "FORBIDDEN", "The operation is not permitted.");
    var rows = db.queryForList("SELECT * FROM payments WHERE payment_id=?", id);
    if (rows.isEmpty()) throw new ApiFailure(404, "PAYMENT_NOT_FOUND", "Payment was not found.");
    var p = rows.getFirst();
    boolean visible = caller.admin() || caller.subject().equals(p.get("requested_by"));
    if (!visible && "COMPLETED".equals(p.get("status"))) {
      var accounts =
          db.queryForList(
              "SELECT * FROM accounts WHERE id IN (?,?)",
              p.get("from_account_id"),
              p.get("to_account_id"));
      visible = accounts.stream().anyMatch(a -> authorized(a, caller));
    }
    if (!visible) throw new ApiFailure(404, "PAYMENT_NOT_FOUND", "Payment was not found.");
    return stored(p, false, true);
  }

  public Map<String, Object> balance(String id, Caller caller) {
    if (!caller.has("accounts:read"))
      throw new ApiFailure(403, "FORBIDDEN", "The operation is not permitted.");
    var rows = db.queryForList("SELECT * FROM accounts WHERE id=?", id);
    if (rows.isEmpty() || !authorized(rows.getFirst(), caller))
      throw new ApiFailure(404, "ACCOUNT_NOT_FOUND", "Account was not found.");
    return Map.of(
        "accountId",
        id,
        "balance",
        ((BigDecimal) rows.getFirst().get("available_balance")).setScale(3).toPlainString(),
        "currency",
        "JOD");
  }
}
