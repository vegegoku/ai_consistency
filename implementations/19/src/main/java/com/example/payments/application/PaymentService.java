package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final PaymentRepository repository;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  public record Result(int http, Map<String, Object> body, String code, boolean replay) {}

  public PaymentService(
      PaymentRepository repository, PlatformTransactionManager manager, MeterRegistry metrics) {
    this.repository = repository;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(8);
    this.metrics = metrics;
  }

  public Result transfer(Command c, String principal) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          Result r = tx.execute(s -> execute(c, principal));
          metrics.counter("payments.outcomes", "code", r.code()).increment();
          if (r.replay()) metrics.counter("payments.replays").increment();
          log.atInfo()
              .addKeyValue("paymentId", c.paymentId())
              .addKeyValue("status", r.body().getOrDefault("status", "REJECTED"))
              .addKeyValue("code", r.code())
              .addKeyValue("latencyMs", (System.nanoTime() - start) / 1_000_000)
              .log("payment outcome committed");
          return r;
        } catch (DataAccessException e) {
          if (!retryable(e)) {
            if (e instanceof DataAccessResourceFailureException) throw unavailable(c);
            throw e;
          }
          if (sqlState(e, "55P03")) metrics.counter("payments.lock.timeouts").increment();
          if (attempt >= 2) throw unavailable(c);
          metrics.counter("payments.retries").increment();
        } catch (org.springframework.transaction.TransactionException e) {
          // Connection or commit uncertainty must never become a durable business rejection.
          throw unavailable(c);
        }
      }
    } finally {
      metrics
          .timer("payments.processing")
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  private ApiException unavailable(Command c) {
    return new ApiException(503, "TEMPORARILY_UNAVAILABLE", c.paymentId().toString(), List.of());
  }

  private boolean retryable(DataAccessException error) {
    return error instanceof TransientDataAccessException
        || sqlState(error, "55P03", "40P01", "40001", "57014");
  }

  private boolean sqlState(Throwable error, String... states) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql) {
        for (String state : states) if (state.equals(sql.getSQLState())) return true;
      }
    }
    return false;
  }

  private Result execute(Command c, String principal) {
    repository.boundedLocks();
    boolean inserted = repository.register(c, principal, correlation());
    var p = repository.lockPayment(c.paymentId());
    if (!inserted) {
      if (!principal.equals(p.get("requested_by"))) return error(403, "FORBIDDEN", c, false);
      if (!c.fingerprint().equals(p.get("payload_fingerprint")))
        return error(409, "IDEMPOTENCY_CONFLICT", c, false);
      return stored(p, true);
    }
    var accounts = repository.lockAccounts(c.fromAccount(), c.toAccount());
    Map<String, Object> from = null, to = null;
    for (var a : accounts) {
      if (c.fromAccount().equals(a.get("id"))) from = a;
      if (c.toAccount().equals(a.get("id"))) to = a;
    }
    String code = null;
    int status = 409;
    if (from == null || !debitAllowed(from, principal)) {
      code = "FORBIDDEN";
      status = 403;
    } else if (!"ACTIVE".equals(from.get("status"))) {
      code = "ACCOUNT_NOT_ACTIVE";
    } else if (to == null
        || !"ACTIVE".equals(to.get("status"))
        || !Boolean.TRUE.equals(to.get("incoming_allowed"))
        || !Boolean.TRUE.equals(from.get("outgoing_allowed"))
        || !"JOD".equals(from.get("currency"))
        || !"JOD".equals(to.get("currency"))) {
      code = "FORBIDDEN";
      status = 403;
    } else if (c.amount().compareTo((BigDecimal) from.get("transfer_limit")) > 0) {
      code = "PAYMENT_LIMIT_EXCEEDED";
    } else if (c.amount().compareTo((BigDecimal) to.get("transfer_limit")) > 0) {
      code = "FORBIDDEN";
      status = 403;
    } else if (((BigDecimal) from.get("available_balance")).compareTo(c.amount()) < 0) {
      code = "INSUFFICIENT_FUNDS";
    }
    if (code != null) {
      repository.reject(c.paymentId(), code, status);
      return stored(repository.payment(c.paymentId()), false);
    }
    BigDecimal source = ((BigDecimal) from.get("available_balance")).subtract(c.amount()),
        target = ((BigDecimal) to.get("available_balance")).add(c.amount());
    if (target.precision() > 19) {
      repository.reject(c.paymentId(), "PAYMENT_LIMIT_EXCEEDED", 409);
      return stored(repository.payment(c.paymentId()), false);
    }
    repository.balance(c.fromAccount(), source);
    repository.balance(c.toAccount(), target);
    repository.ledger(c, source, target);
    repository.complete(c.paymentId());
    return stored(repository.payment(c.paymentId()), false);
  }

  private UUID correlation() {
    try {
      return UUID.fromString(MDC.get("correlationId"));
    } catch (Exception e) {
      return UUID.randomUUID();
    }
  }

  private boolean debitAllowed(Map<String, Object> a, String p) {
    return p.equals(a.get("customer_id")) || repository.granted(a.get("id").toString(), p, true);
  }

  private boolean viewAllowed(Map<String, Object> a, String p) {
    return p.equals(a.get("customer_id")) || repository.granted(a.get("id").toString(), p, false);
  }

  private Result error(int http, String code, Command c, boolean replay) {
    return new Result(
        http,
        Map.of(
            "paymentId",
            c.paymentId().toString(),
            "status",
            "REJECTED",
            "code",
            code,
            "message",
            "The payment could not be completed.",
            "issues",
            List.of(
                new ApiException.Issue("payment", code, "The payment could not be completed."))),
        code,
        replay);
  }

  private Result stored(Map<String, Object> p, boolean replay) {
    if ("REJECTED".equals(p.get("status")))
      return new Result(
          ((Number) p.get("rejection_http")).intValue(),
          Map.of(
              "paymentId",
              p.get("payment_id").toString(),
              "status",
              "REJECTED",
              "code",
              p.get("rejection_code"),
              "message",
              "The payment could not be completed.",
              "issues",
              List.of(
                  new ApiException.Issue(
                      "payment",
                      p.get("rejection_code").toString(),
                      "The payment could not be completed.")),
              "timestamp",
              ((Timestamp) p.get("processed_at")).toInstant().toString()),
          p.get("rejection_code").toString(),
          replay);
    var b = new LinkedHashMap<String, Object>();
    b.put("paymentId", p.get("payment_id").toString());
    b.put("status", "COMPLETED");
    b.put("fromAccount", p.get("from_account_id"));
    b.put("toAccount", p.get("to_account_id"));
    b.put("amount", ((BigDecimal) p.get("amount")).toPlainString());
    b.put("currency", "JOD");
    b.put("processedAt", ((Timestamp) p.get("processed_at")).toInstant().toString());
    return new Result(replay ? 200 : 201, b, "COMPLETED", replay);
  }

  public Map<String, Object> find(UUID id, String principal) {
    var rows = repository.findPayment(id);
    if (rows.isEmpty() || !principal.equals(rows.getFirst().get("requested_by")))
      throw new ApiException(404, "PAYMENT_NOT_FOUND");
    return stored(rows.getFirst(), true).body();
  }

  public Map<String, Object> balance(String id, String principal) {
    var rows = repository.findAccount(id);
    if (rows.isEmpty() || !viewAllowed(rows.getFirst(), principal))
      throw new ApiException(404, "ACCOUNT_NOT_FOUND");
    return Map.of(
        "accountId",
        id,
        "balance",
        ((BigDecimal) rows.getFirst().get("available_balance")).toPlainString(),
        "currency",
        "JOD");
  }
}
