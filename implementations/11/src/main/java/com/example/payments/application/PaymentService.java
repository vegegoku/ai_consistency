package com.example.payments.application;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  public record Outcome(int http, Map<String, Object> body, boolean replayed) {
    public Outcome(int http, Map<String, Object> body) {
      this(http, body, false);
    }
  }

  private record Account(
      String id,
      String owner,
      String status,
      BigDecimal balance,
      BigDecimal limit,
      boolean incoming,
      String customerStatus) {}

  @org.springframework.beans.factory.annotation.Value("${payment.max-future-seconds:300}")
  private long futureSeconds = 300;

  @org.springframework.beans.factory.annotation.Value("${payment.max-age-seconds:2592000}")
  private long ageSeconds = 2592000;

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json = new ObjectMapper();
  private final ObjectProvider<TransferHook> hooks;
  private final MeterRegistry metrics;
  private final com.example.payments.persistence.FinancialQueries queries;

  public PaymentService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ObjectProvider<TransferHook> hooks,
      MeterRegistry metrics,
      com.example.payments.persistence.FinancialQueries queries) {
    this.queries = queries;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    tx.setTimeout(10);
    this.hooks = hooks;
    this.metrics = metrics;
  }

  public Outcome transfer(PaymentCommand c, String principal, String correlation) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          // TransactionTemplate commits before any receipt or post-commit telemetry escapes.
          Outcome result = tx.execute(s -> execute(c, principal, correlation));
          try {
            metrics
                .counter(
                    result.replayed()
                        ? "payments.replays"
                        : result.http() == 201 ? "payments.completed" : "payments.rejected",
                    "code",
                    Objects.toString(result.body().get("code"), "NONE"))
                .increment();
          } catch (RuntimeException ignored) {
          }
          return result;
        } catch (DataAccessException ex) {
          String state = sqlState(ex);
          if (Set.of("40P01", "40001", "55P03", "57014").contains(state)) {
            metrics.counter("payments.lock.failures").increment();
            if (attempt < 2) {
              metrics.counter("payments.retries").increment();
              continue;
            }
          }
          throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE", List.of(), c.paymentId());
        }
      }
    } catch (RuntimeException ex) {
      try {
        if (!(ex instanceof ApiFailure))
          metrics.counter("payments.unexpected.failures").increment();
      } catch (RuntimeException ignored) {
      }
      throw ex;
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private String sqlState(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause())
      if (t instanceof java.sql.SQLException s) return Objects.toString(s.getSQLState(), "");
    return "";
  }

  private Outcome execute(PaymentCommand c, String who, String correlation) {
    db.execute("SET LOCAL lock_timeout='1500ms'");
    db.execute("SET LOCAL statement_timeout='5000ms'");
    UUID id = UUID.fromString(c.paymentId());
    // PostgreSQL unique insertion serializes concurrent requests for the same UUID.
    int inserted =
        db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES (?,?,?,?,?,?,?,?, 'PENDING',?,?) ON CONFLICT DO NOTHING",
            id,
            c.fromAccount(),
            c.toAccount(),
            c.amount(),
            Timestamp.from(c.date()),
            c.purpose(),
            c.notes(),
            c.fingerprint(),
            who,
            correlation);
    if (inserted == 0) {
      var row =
          db.queryForMap(
              "SELECT requested_by,payload_fingerprint,http_status,response_json FROM payments"
                  + " WHERE payment_id=?",
              id);
      if (!who.equals(row.get("requested_by")))
        return error(403, "FORBIDDEN", c.paymentId(), correlation);
      if (!c.fingerprint().equals(row.get("payload_fingerprint")))
        return error(409, "IDEMPOTENCY_CONFLICT", c.paymentId(), correlation);
      try {
        int code = ((Number) row.get("http_status")).intValue();
        return new Outcome(
            code == 201 ? 200 : code,
            json.readValue(
                (String) row.get("response_json"), new TypeReference<Map<String, Object>>() {}),
            true);
      } catch (Exception e) {
        throw new IllegalStateException("Stored response invalid", e);
      }
    }
    if (c.date().isAfter(Instant.now().plusSeconds(futureSeconds))
        || c.date().isBefore(Instant.now().minusSeconds(ageSeconds)))
      throw new ApiFailure(
          400,
          "VALIDATION_FAILED",
          List.of(
              new ApiFailure.Issue(
                  "date", "OUTSIDE_WINDOW", "The timestamp is outside the allowed window.")),
          c.paymentId());
    // Global account ordering is independent of debit direction, avoiding AB/BA deadlocks.
    List<Account> accounts =
        db.query(
            "SELECT a.*,c.status AS customer_status FROM accounts a JOIN customers c ON"
                + " c.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a",
            (r, n) ->
                new Account(
                    r.getString("id"),
                    r.getString("customer_id"),
                    r.getString("status"),
                    r.getBigDecimal("available_balance"),
                    r.getBigDecimal("transfer_limit"),
                    r.getBoolean("allow_incoming"),
                    r.getString("customer_status")),
            c.fromAccount(),
            c.toAccount());
    Account
        from = accounts.stream().filter(a -> a.id.equals(c.fromAccount())).findFirst().orElse(null),
        to = accounts.stream().filter(a -> a.id.equals(c.toAccount())).findFirst().orElse(null);
    String code = null;
    int status = 409;
    if (from == null || !canDebit(from, who)) {
      code = "FORBIDDEN";
      status = 403;
    } else if (to == null) {
      code = "FORBIDDEN";
      status = 403;
    } else if (!to.incoming) {
      code = "FORBIDDEN";
      status = 403;
    } else if (!from.status.equals("ACTIVE")
        || !to.status.equals("ACTIVE")
        || !from.customerStatus.equals("ACTIVE")
        || !to.customerStatus.equals("ACTIVE")) code = "ACCOUNT_NOT_ACTIVE";
    else if (c.amount().compareTo(from.limit) > 0 || c.amount().compareTo(to.limit) > 0)
      code = "PAYMENT_LIMIT_EXCEEDED";
    else if (to.balance.add(c.amount()).compareTo(new BigDecimal("9999999999999999.999")) > 0)
      code = "PAYMENT_LIMIT_EXCEEDED";
    else if (from.balance.compareTo(c.amount()) < 0) code = "INSUFFICIENT_FUNDS";
    if (code != null) {
      Outcome outcome = error(status, code, c.paymentId(), correlation);
      store(id, "REJECTED", code, outcome);
      db.update(
          "INSERT INTO payment_issues(payment_id,code,safe_message,correlation_id) VALUES"
              + " (?,?,?,?)",
          id,
          code,
          "The payment could not be completed.",
          correlation);
      return outcome;
    }
    BigDecimal debit = from.balance.subtract(c.amount()), credit = to.balance.add(c.amount());
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        debit,
        from.id);
    hooks.orderedStream().forEach(TransferHook::afterDebit);
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        credit,
        to.id);
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES (?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
        id,
        from.id,
        c.amount().negate(),
        debit,
        id,
        to.id,
        c.amount(),
        credit);
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("paymentId", c.paymentId());
    receipt.put("status", "COMPLETED");
    receipt.put("fromAccount", c.fromAccount());
    receipt.put("toAccount", c.toAccount());
    receipt.put("amount", c.amount().toPlainString());
    receipt.put("currency", "JOD");
    receipt.put("processedAt", Instant.now().toString());
    Outcome result = new Outcome(201, receipt);
    store(id, "COMPLETED", null, result);
    return result;
  }

  private boolean canDebit(Account a, String who) {
    return a.owner.equals(who)
        || Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM account_permissions WHERE principal=? AND account_id=?"
                    + " AND can_debit)",
                Boolean.class,
                who,
                a.id));
  }

  private void store(UUID id, String status, String code, Outcome result) {
    try {
      db.update(
          "UPDATE payments SET"
              + " status=?,rejection_code=?,processed_at=now(),http_status=?,response_json=? WHERE"
              + " payment_id=?",
          status,
          code,
          result.http,
          json.writeValueAsString(result.body),
          id);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Outcome error(int http, String code, String id, String correlation) {
    Map<String, Object> error = new LinkedHashMap<>();
    if (id != null) error.put("paymentId", id);
    error.put("status", http == 409 || http == 403 ? "REJECTED" : "ERROR");
    error.put("code", code);
    error.put("message", "The payment could not be completed.");
    error.put("issues", List.of());
    error.put("correlationId", correlation);
    error.put("timestamp", Instant.now().toString());
    return new Outcome(http, error);
  }

  public Outcome find(String paymentId, String who) {
    UUID id;
    try {
      id = UUID.fromString(paymentId);
    } catch (Exception e) {
      throw new ApiFailure(404, "NOT_FOUND");
    }
    String stored = queries.payment(id, who).orElseThrow(() -> new ApiFailure(404, "NOT_FOUND"));
    try {
      return new Outcome(200, json.readValue(stored, new TypeReference<Map<String, Object>>() {}));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> balance(String account, String who) {
    return Map.of(
        "accountId",
        account,
        "availableBalance",
        queries
            .balance(account, who)
            .orElseThrow(() -> new ApiFailure(404, "NOT_FOUND"))
            .toPlainString(),
        "currency",
        "JOD");
  }

  public List<Map<String, Object>> reconcile() {
    return queries.reconcile();
  }

  public Map<String, Object> retention(int days) {
    return queries.retention(days);
  }
}
