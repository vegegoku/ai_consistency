package com.example.payments.application;

import static com.example.payments.persistence.JdbcRepository.*;

import com.example.payments.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PaymentService {
  @Inject DataSource db;
  @Inject MeterRegistry metrics;
  @Inject FailureInjector faults;
  @Inject Settings settings;

  public record Result(int http, Map<String, Object> body) {}

  public Result transfer(Command cmd, String principal, String correlation) {
    long start = System.nanoTime();
    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection c = db.getConnection()) {
        c.setAutoCommit(false);
        try {
          execute(c, "SET LOCAL lock_timeout='2000ms'");
          execute(c, "SET LOCAL statement_timeout='5000ms'");
          int inserted =
              update(
                  c,
                  "INSERT INTO"
                      + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                      + " VALUES(?,?,?,?,?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO"
                      + " NOTHING",
                  cmd.paymentId(),
                  cmd.fromAccount(),
                  cmd.toAccount(),
                  cmd.amount(),
                  Timestamp.from(cmd.date()),
                  cmd.purpose(),
                  cmd.notes(),
                  cmd.fingerprint(),
                  principal,
                  UUID.fromString(correlation));
          if (inserted == 0) {
            Map<String, Object> p =
                row(c, "SELECT * FROM payments WHERE payment_id=?", cmd.paymentId());
            if (!principal.equals(p.get("requested_by")))
              throw new Problem(404, "PAYMENT_NOT_FOUND");
            if (!cmd.fingerprint().equals(p.get("payload_fingerprint")))
              throw new Problem(409, "IDEMPOTENCY_CONFLICT", cmd.paymentId().toString(), List.of());
            Result result = stored(p, 200);
            c.commit();
            metrics.counter("payments.replays").increment();
            return result;
          }
          TreeMap<String, Map<String, Object>> accounts = new TreeMap<>();
          for (String id : new TreeSet<>(List.of(cmd.fromAccount(), cmd.toAccount())))
            accounts.put(id, row(c, "SELECT * FROM accounts WHERE id=? FOR UPDATE", id));
          var from = accounts.get(cmd.fromAccount());
          var to = accounts.get(cmd.toAccount());
          String rejection = null;
          if (from == null || !authorized(c, from, principal, "can_debit")) rejection = "FORBIDDEN";
          else if (to == null) rejection = "FORBIDDEN";
          else if (!"ACTIVE".equals(from.get("status")) || !"ACTIVE".equals(to.get("status")))
            rejection = "ACCOUNT_NOT_ACTIVE";
          else if (!"JOD".equals(from.get("currency"))
              || !"JOD".equals(to.get("currency"))
              || !Boolean.TRUE.equals(to.get("receive_allowed"))) rejection = "FORBIDDEN";
          else if (cmd.amount().compareTo((BigDecimal) from.get("debit_limit")) > 0)
            rejection = "PAYMENT_LIMIT_EXCEEDED";
          else if (((BigDecimal) from.get("available_balance")).compareTo(cmd.amount()) < 0)
            rejection = "INSUFFICIENT_FUNDS";
          if (rejection != null) {
            update(
                c,
                "UPDATE payments SET"
                    + " status='REJECTED',rejection_code=?,processed_at=clock_timestamp() WHERE"
                    + " payment_id=?",
                rejection,
                cmd.paymentId());
          } else {
            BigDecimal debit = ((BigDecimal) from.get("available_balance")).subtract(cmd.amount()),
                credit = ((BigDecimal) to.get("available_balance")).add(cmd.amount());
            update(
                c,
                "UPDATE accounts SET"
                    + " available_balance=?,version=version+1,updated_at=clock_timestamp() WHERE"
                    + " id=?",
                debit,
                cmd.fromAccount());
            faults.at("AFTER_DEBIT");
            update(
                c,
                "UPDATE accounts SET"
                    + " available_balance=?,version=version+1,updated_at=clock_timestamp() WHERE"
                    + " id=?",
                credit,
                cmd.toAccount());
            faults.at("AFTER_CREDIT");
            update(
                c,
                "INSERT INTO"
                    + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                    + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
                cmd.paymentId(),
                cmd.fromAccount(),
                cmd.amount().negate(),
                debit,
                cmd.paymentId(),
                cmd.toAccount(),
                cmd.amount(),
                credit);
            faults.at("AFTER_LEDGER");
            update(
                c,
                "UPDATE payments SET status='COMPLETED',processed_at=clock_timestamp() WHERE"
                    + " payment_id=?",
                cmd.paymentId());
          }
          var p = row(c, "SELECT * FROM payments WHERE payment_id=?", cmd.paymentId());
          Result result = stored(p, 201);
          c.commit();
          try {
            metrics
                .counter("payments.outcomes", "code", rejection == null ? "COMPLETED" : rejection)
                .increment();
            metrics.timer("payments.latency").record(Duration.ofNanos(System.nanoTime() - start));
            Logger.getLogger(PaymentService.class)
                .infof(
                    "correlationId=%s paymentId=%s status=%s code=%s latencyMs=%d",
                    correlation,
                    cmd.paymentId(),
                    p.get("status"),
                    rejection,
                    (System.nanoTime() - start) / 1000000);
          } catch (RuntimeException ignored) {
          }
          try {
            faults.at("AFTER_COMMIT");
          } catch (RuntimeException e) {
            throw new Problem(
                503, "TEMPORARILY_UNAVAILABLE", cmd.paymentId().toString(), List.of());
          }
          return result;
        } catch (Exception e) {
          c.rollback();
          throw e;
        }
      } catch (Problem e) {
        throw e;
      } catch (SQLException e) {
        if (e.getSQLState() != null
            && Set.of("40P01", "40001", "55P03", "57014").contains(e.getSQLState())) {
          metrics.counter("payments.lock.timeouts").increment();
          if (attempt < 2) {
            metrics.counter("payments.retries").increment();
            continue;
          }
        }
        throw new Problem(503, "TEMPORARILY_UNAVAILABLE", cmd.paymentId().toString(), List.of());
      } catch (RuntimeException e) {
        throw new Problem(500, "INTERNAL_ERROR", cmd.paymentId().toString(), List.of());
      }
    }
    throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
  }

  public void checkRate(String principal) {
    try (Connection c = db.getConnection()) {
      var r =
          row(
              c,
              "INSERT INTO request_limits(principal,minute,count)"
                  + " VALUES(?,date_trunc('minute',now()),1) ON CONFLICT(principal) DO UPDATE SET"
                  + " minute=excluded.minute,count=CASE WHEN request_limits.minute=excluded.minute"
                  + " THEN request_limits.count+1 ELSE 1 END RETURNING count",
              principal);
      if (((Number) r.get("count")).intValue() > settings.rate)
        throw new Problem(429, "RATE_LIMITED");
    } catch (SQLException e) {
      throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  /**
   * Policy-window preflight for old retries; the transactional unique-key guard remains
   * authoritative.
   */
  public boolean isRecorded(UUID id, String principal) {
    try (Connection c = db.getConnection()) {
      return row(
              c,
              "SELECT payment_id FROM payments WHERE payment_id=? AND requested_by=? AND status IN"
                  + " ('COMPLETED','REJECTED')",
              id,
              principal)
          != null;
    } catch (SQLException e) {
      throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Result get(UUID id, String principal) {
    try (Connection c = db.getConnection()) {
      var p = row(c, "SELECT * FROM payments WHERE payment_id=?", id);
      if (p == null || !principal.equals(p.get("requested_by")))
        throw new Problem(404, "PAYMENT_NOT_FOUND");
      return stored(p, 200);
    } catch (SQLException e) {
      throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, String principal) {
    try (Connection c = db.getConnection()) {
      var a = row(c, "SELECT * FROM accounts WHERE id=?", id);
      if (a == null || !authorized(c, a, principal, "can_debit"))
        throw new Problem(404, "ACCOUNT_NOT_FOUND");
      return Map.of(
          "accountId",
          id,
          "availableBalance",
          ((BigDecimal) a.get("available_balance")).setScale(3).toPlainString(),
          "currency",
          a.get("currency"));
    } catch (SQLException e) {
      throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public List<Map<String, Object>> reconcile() {
    try (Connection c = db.getConnection();
        PreparedStatement p = c.prepareStatement("SELECT * FROM reconciliation_issues")) {
      p.setQueryTimeout(5);
      try (ResultSet r = p.executeQuery()) {
        List<Map<String, Object>> list = new ArrayList<>();
        while (r.next()) list.add(Map.of("kind", r.getString(1), "identifier", r.getString(2)));
        return list;
      }
    } catch (SQLException e) {
      throw new Problem(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  private boolean authorized(
      Connection c, Map<String, Object> account, String principal, String permission)
      throws SQLException {
    return principal.equals(account.get("customer_id"))
        || row(
                c,
                "SELECT account_id FROM account_permissions WHERE account_id=? AND principal=? AND "
                    + permission
                    + "=true",
                account.get("id"),
                principal)
            != null;
  }

  private Result stored(Map<String, Object> p, int success) {
    String id = p.get("payment_id").toString();
    if ("REJECTED".equals(p.get("status"))) {
      String code = p.get("rejection_code").toString();
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("paymentId", id);
      b.put("status", "REJECTED");
      b.put("code", code);
      b.put("message", "The payment could not be completed.");
      b.put("issues", List.of());
      b.put("correlationId", p.get("correlation_id").toString());
      b.put("timestamp", ((Timestamp) p.get("processed_at")).toInstant().toString());
      return new Result(code.equals("FORBIDDEN") ? 403 : 409, b);
    }
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("paymentId", id);
    b.put("status", "COMPLETED");
    b.put("fromAccount", p.get("from_account_id"));
    b.put("toAccount", p.get("to_account_id"));
    b.put("amount", ((BigDecimal) p.get("amount")).setScale(3).toPlainString());
    b.put("currency", "JOD");
    b.put("processedAt", ((Timestamp) p.get("processed_at")).toInstant().toString());
    return new Result(success, b);
  }
}
