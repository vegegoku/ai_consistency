package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.PaymentStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  public interface FaultInjector {
    void afterDebit();
  }

  public record Result(int http, Map<String, Object> body, boolean replay) {}

  private final PaymentStore store;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private final BigDecimal daily;
  private final FaultInjector fault;
  private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

  public PaymentService(
      PaymentStore store,
      TransactionTemplate tx,
      MeterRegistry metrics,
      @Value("${payments.daily-limit:50000.000}") BigDecimal daily,
      FaultInjector fault) {
    this.store = store;
    this.tx = tx;
    this.metrics = metrics;
    this.daily = daily;
    this.fault = fault;
  }

  public Result transfer(PaymentCommand command, String principal, String correlation) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          Result result = tx.execute(s -> execute(command, principal, correlation));
          try {
            metrics
                .counter(
                    result.replay ? "payments.replays" : "payments.outcomes",
                    "status",
                    result.body.get("status").toString(),
                    "code",
                    result.body.getOrDefault("code", "NONE").toString())
                .increment();
            LOG.atInfo()
                .addKeyValue("paymentId", command.paymentId())
                .addKeyValue("correlationId", correlation)
                .addKeyValue("status", result.body.get("status"))
                .addKeyValue("code", result.body.getOrDefault("code", "NONE"))
                .addKeyValue("latencyMs", (System.nanoTime() - start) / 1_000_000)
                .log("Payment processed");
          } catch (RuntimeException telemetryFailure) {
            /* Financial commit is already durable. */
          }
          return result;
        } catch (DataAccessException e) {
          String state = sqlState(e);
          if (Set.of("40001", "40P01", "55P03", "57014").contains(state)) {
            metrics.counter("payments.lock.timeouts").increment();
            if (attempt < 2) {
              metrics.counter("payments.retries").increment();
              try {
                Thread.sleep(25L * (attempt + 1));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
              }
              continue;
            }
          }
          throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
        }
      }
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException telemetryFailure) {
        /* Telemetry cannot change the financial outcome. */
      }
    }
  }

  private String sqlState(Throwable error) {
    while (error != null) {
      if (error instanceof SQLException s) return Objects.toString(s.getSQLState(), "");
      error = error.getCause();
    }
    return "";
  }

  private Result execute(PaymentCommand c, String principal, String correlation) {
    store.boundedTransfer();
    boolean inserted = store.register(c, principal, correlation);
    var p = store.lockPayment(c.paymentId());
    if (!inserted) {
      if (!principal.equals(p.get("requested_by"))) throw new PaymentException(403, "FORBIDDEN");
      if (!c.fingerprint().equals(p.get("payload_fingerprint")))
        throw new PaymentException(409, "IDEMPOTENCY_CONFLICT", List.of(), c.paymentId());
      return receipt(p, true);
    }
    var rows = store.lockAccounts(c.fromAccount(), c.toAccount());
    Map<String, Object> from = null, to = null;
    for (var a : rows) {
      if (a.get("id").equals(c.fromAccount())) from = a;
      if (a.get("id").equals(c.toAccount())) to = a;
    }
    if (from == null
        || (!principal.equals(from.get("customer_id"))
            && !store.hasDebitGrant(c.fromAccount(), principal))) return reject(c, "FORBIDDEN");
    if (to == null || !Boolean.TRUE.equals(to.get("receive_allowed")))
      return reject(c, "FORBIDDEN");
    if (!"ACTIVE".equals(from.get("status")) || !"ACTIVE".equals(to.get("status")))
      return reject(c, "ACCOUNT_NOT_ACTIVE");
    if (!"JOD".equals(from.get("currency")) || !"JOD".equals(to.get("currency")))
      return reject(c, "FORBIDDEN");
    if (!store.activeCustomer(from.get("customer_id").toString())) return reject(c, "FORBIDDEN");
    BigDecimal used = store.spentToday(c.fromAccount());
    BigDecimal limit =
        from.get("daily_limit") == null ? daily : daily.min((BigDecimal) from.get("daily_limit"));
    if (used.add(c.amount()).compareTo(limit) > 0) return reject(c, "PAYMENT_LIMIT_EXCEEDED");
    BigDecimal debit = ((BigDecimal) from.get("available_balance")).subtract(c.amount());
    if (debit.signum() < 0) return reject(c, "INSUFFICIENT_FUNDS");
    BigDecimal credit = ((BigDecimal) to.get("available_balance")).add(c.amount());
    store.balance(c.fromAccount(), debit);
    fault.afterDebit();
    store.balance(c.toAccount(), credit);
    store.appendLedger(c, debit, credit);
    store.complete(c.paymentId());
    return receipt(store.payment(c.paymentId()), false);
  }

  private Result reject(PaymentCommand c, String code) {
    store.reject(c.paymentId(), code);
    return receipt(store.payment(c.paymentId()), false);
  }

  private Result receipt(Map<String, Object> p, boolean replay) {
    var body = new LinkedHashMap<String, Object>();
    body.put("paymentId", p.get("payment_id").toString());
    body.put("status", p.get("status"));
    if ("REJECTED".equals(p.get("status"))) {
      String code = p.get("rejection_code").toString();
      body.put("code", code);
      body.put("message", "The payment could not be completed.");
      body.put("issues", List.of());
      body.put("correlationId", p.get("correlation_id").toString());
      body.put("timestamp", instant(p.get("processed_at")));
      return new Result(code.equals("FORBIDDEN") ? 403 : 409, body, replay);
    }
    body.put("fromAccount", p.get("from_account_id"));
    body.put("toAccount", p.get("to_account_id"));
    body.put("amount", ((BigDecimal) p.get("amount")).setScale(3).toPlainString());
    body.put("currency", "JOD");
    body.put("processedAt", instant(p.get("processed_at")));
    return new Result(replay ? 200 : 201, body, replay);
  }

  private String instant(Object o) {
    return o instanceof Timestamp t
        ? t.toInstant().toString()
        : ((OffsetDateTime) o).toInstant().toString();
  }

  public Map<String, Object> get(String id, String principal) {
    UUID key;
    try {
      key = UUID.fromString(id);
    } catch (Exception e) {
      throw new PaymentException(404, "NOT_FOUND");
    }
    var rows = store.visiblePayment(key, principal);
    if (rows.isEmpty()) throw new PaymentException(404, "NOT_FOUND");
    return receipt(rows.getFirst(), true).body;
  }

  public Map<String, Object> balance(String account, String principal) {
    var rows = store.visibleBalance(account, principal);
    if (rows.isEmpty()) throw new PaymentException(404, "NOT_FOUND");
    return Map.of(
        "accountId",
        account,
        "balance",
        ((BigDecimal) rows.getFirst().get("available_balance")).setScale(3).toPlainString(),
        "currency",
        "JOD");
  }

  public Map<String, Object> reconcile() {
    return tx.execute(
        s -> {
          store.reconciliationSnapshot();
          var payments = store.unbalancedPayments();
          var accounts = store.unbalancedAccounts();
          return Map.of(
              "healthy",
              payments.isEmpty() && accounts.isEmpty(),
              "payments",
              payments,
              "accounts",
              accounts,
              "timestamp",
              Instant.now().toString());
        });
  }
}
