package com.example.payments.application;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final com.example.payments.persistence.PaymentRepository repository;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

  public record Result(int http, Map<String, Object> body, boolean replay) {}

  public PaymentService(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      ObjectMapper json,
      MeterRegistry metrics,
      com.example.payments.persistence.PaymentRepository repository) {
    this.repository = repository;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
    this.metrics = metrics;
    tx.setTimeout(5);
  }

  public Result transfer(PaymentCommand c, String principal, String correlation) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          Result result = tx.execute(s -> execute(c, principal, correlation));
          safeMetric(result);
          LOG.atInfo()
              .addKeyValue("paymentId", c.paymentId())
              .addKeyValue("correlationId", correlation)
              .addKeyValue("principal", principal)
              .addKeyValue("status", result.body.get("status"))
              .addKeyValue("code", result.body.getOrDefault("code", "COMPLETED"))
              .addKeyValue("latencyMs", (System.nanoTime() - start) / 1000000)
              .log("Payment outcome committed");
          return result;
        } catch (DataAccessException e) {
          if (!transientFailure(e)) {
            metrics.counter("payments.unexpected").increment();
            throw new ApiError(500, "INTERNAL_ERROR", c.paymentId().toString(), List.of());
          }
          metrics.counter("payments.retries").increment();
          if (attempt == 2)
            throw new ApiError(503, "TEMPORARILY_UNAVAILABLE", c.paymentId().toString(), List.of());
        }
      }
      throw new IllegalStateException();
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
        LOG.warn("Processing metric failed");
      }
    }
  }

  private void safeMetric(Result r) {
    try {
      metrics
          .counter("payments.outcomes", "code", r.body.getOrDefault("code", "COMPLETED").toString())
          .increment();
      if (r.replay) metrics.counter("payments.replays").increment();
    } catch (RuntimeException ignored) {
      LOG.warn("Payment telemetry failed");
    }
  }

  private Result execute(PaymentCommand c, String who, String correlation) {
    db.execute("SET LOCAL lock_timeout = '1500ms'");
    String fingerprint = fingerprint(c);
    boolean inserted = repository.register(c, fingerprint, who, correlation);
    if (!inserted) {
      Map<String, Object> existing =
          db.queryForMap("SELECT * FROM payments WHERE payment_id=?", c.paymentId());
      if (!existing.get("requested_by").equals(who)) throw new ApiError(403, "FORBIDDEN");
      if (!existing.get("payload_fingerprint").equals(fingerprint))
        throw new ApiError(409, "IDEMPOTENCY_CONFLICT", c.paymentId().toString(), List.of());
      return new Result(
          ((Number) existing.get("http_status")).intValue() == 201
              ? 200
              : ((Number) existing.get("http_status")).intValue(),
          decode(existing.get("outcome").toString()),
          true);
    }
    List<Map<String, Object>> accounts = repository.lockAccounts(c.fromAccount(), c.toAccount());
    Map<String, Object>
        from =
            accounts.stream()
                .filter(a -> a.get("id").equals(c.fromAccount()))
                .findFirst()
                .orElse(null),
        to =
            accounts.stream()
                .filter(a -> a.get("id").equals(c.toAccount()))
                .findFirst()
                .orElse(null);
    if (from == null || !authorized(who, from, true))
      return reject(c, 403, "FORBIDDEN", correlation);
    if (to == null) return reject(c, 403, "FORBIDDEN", correlation);
    if (!from.get("status").equals("ACTIVE") || !to.get("status").equals("ACTIVE"))
      return reject(c, 409, "ACCOUNT_NOT_ACTIVE", correlation);
    if (!from.get("customer_status").equals("ACTIVE")
        || !to.get("customer_status").equals("ACTIVE")
        || !Boolean.TRUE.equals(to.get("allow_receive"))
        || !from.get("currency").equals("JOD")
        || !to.get("currency").equals("JOD")) return reject(c, 403, "FORBIDDEN", correlation);
    if (c.amount().compareTo((BigDecimal) from.get("transfer_limit")) > 0)
      return reject(c, 409, "PAYMENT_LIMIT_EXCEEDED", correlation);
    BigDecimal balance = (BigDecimal) from.get("available_balance");
    if (balance.compareTo(c.amount()) < 0) return reject(c, 409, "INSUFFICIENT_FUNDS", correlation);
    BigDecimal debit = balance.subtract(c.amount()),
        credit = ((BigDecimal) to.get("available_balance")).add(c.amount());
    repository.updateBalance(c.fromAccount(), debit);
    repository.updateBalance(c.toAccount(), credit);
    repository.appendLedger(c, debit, credit);
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("paymentId", c.paymentId().toString());
    receipt.put("status", "COMPLETED");
    receipt.put("fromAccount", c.fromAccount());
    receipt.put("toAccount", c.toAccount());
    receipt.put("amount", c.amount().toPlainString());
    receipt.put("currency", "JOD");
    receipt.put("processedAt", Instant.now().toString());
    save(c, "COMPLETED", null, receipt, 201);
    return new Result(201, receipt, false);
  }

  private Result reject(PaymentCommand c, int http, String code, String correlation) {
    Map<String, Object> body =
        new ApiError(
                http,
                code,
                c.paymentId().toString(),
                List.of(
                    Map.of(
                        "field",
                        code.equals("INSUFFICIENT_FUNDS") ? "amount" : "payment",
                        "code",
                        code,
                        "message",
                        "The payment could not be completed.")))
            .body(correlation);
    body.put("status", "REJECTED");
    save(c, "REJECTED", code, body, http);
    return new Result(http, body, false);
  }

  private void save(
      PaymentCommand c, String status, String code, Map<String, Object> result, int http) {
    repository.finalizePayment(c.paymentId(), status, code, encode(result), http);
  }

  private boolean authorized(String who, Map<String, Object> account, boolean debit) {
    if (who.equals(account.get("customer_id"))) return true;
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account_permissions WHERE principal=? AND account_id=? AND"
                + " "
                + (debit ? "can_debit" : "can_view")
                + ")",
            Boolean.class,
            who,
            account.get("id")));
  }

  public Map<String, Object> balance(String id, String who) {
    List<Map<String, Object>> list = db.queryForList("SELECT * FROM accounts WHERE id=?", id);
    if (list.isEmpty() || !authorized(who, list.getFirst(), false))
      throw new ApiError(404, "ACCOUNT_NOT_FOUND");
    return Map.of(
        "accountId",
        id,
        "balance",
        ((BigDecimal) list.getFirst().get("available_balance")).setScale(3).toPlainString(),
        "currency",
        "JOD");
  }

  public Map<String, Object> find(UUID id, String who) {
    List<Map<String, Object>> list =
        db.queryForList("SELECT * FROM payments WHERE payment_id=? AND requested_by=?", id, who);
    if (list.isEmpty()) throw new ApiError(404, "PAYMENT_NOT_FOUND");
    return decode(list.getFirst().get("outcome").toString());
  }

  public Map<String, Object> reconcile() {
    return tx.execute(
        s -> {
          db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
          List<Map<String, Object>> payments =
              db.queryForList(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " p.payment_id=l.payment_id WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id,p.amount,p.from_account_id,p.to_account_id HAVING"
                      + " count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR count(*)"
                      + " FILTER(WHERE l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                      + " l.signed_amount=-p.amount)<>1 OR count(*) FILTER(WHERE"
                      + " l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND"
                      + " l.signed_amount=p.amount)<>1");
          List<Map<String, Object>> accounts =
              db.queryForList(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON a.id=l.account_id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)");
          return Map.of(
              "balanced",
              payments.isEmpty() && accounts.isEmpty(),
              "paymentDiscrepancies",
              payments,
              "accountDiscrepancies",
              accounts,
              "checkedAt",
              Instant.now().toString());
        });
  }

  private String fingerprint(PaymentCommand c) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      encode(
                              Arrays.asList(
                                  c.paymentId().toString(),
                                  c.fromAccount(),
                                  c.toAccount(),
                                  c.amount().toPlainString(),
                                  c.date().toString(),
                                  c.purpose(),
                                  c.notes()))
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> decode(String s) {
    try {
      return json.readValue(s, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean transientFailure(Throwable e) {
    if (e instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) return true;
    while (e != null) {
      if (e instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && (state.startsWith("08")
                || Set.of("40P01", "40001", "55P03", "57014", "57P01").contains(state))) {
          if (state.equals("55P03")) metrics.counter("payments.lock.timeouts").increment();
          return true;
        }
      }
      e = e.getCause();
    }
    return false;
  }
}
