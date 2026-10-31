package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.AccountStore;
import com.example.payments.persistence.AccountStore.Account;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PaymentService {
  @Inject DataSource db;
  @Inject AccountStore store;
  @Inject TransferProbe probe;
  @Inject MeterRegistry metrics;
  private static final Logger LOG = Logger.getLogger(PaymentService.class);

  public Outcome transfer(PaymentCommand command, Caller caller, String correlation) {
    caller.require("payments:write");
    long begin = System.nanoTime();
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          Outcome result =
              QuarkusTransaction.requiringNew().call(() -> execute(command, caller, correlation));
          try {
            metrics
                .counter(
                    result.httpStatus() == 201
                        ? "payments.completed"
                        : result.httpStatus() == 200 ? "payments.replays" : "payments.rejected",
                    "code",
                    String.valueOf(result.body().getOrDefault("code", "NONE")))
                .increment();
            LOG.infof(
                "paymentId=%s correlationId=%s status=%s code=%s latencyMs=%d",
                command.paymentId(),
                correlation,
                result.body().get("status"),
                result.body().getOrDefault("code", "NONE"),
                (System.nanoTime() - begin) / 1_000_000);
          } catch (RuntimeException ignored) {
            /* Telemetry must never change a committed outcome. */
          }
          return result;
        } catch (RuntimeException failure) {
          String state = sqlState(failure);
          if (state != null
              && (state.equals("40P01") || state.equals("40001") || state.equals("55P03"))) {
            metrics.counter("payments.retries").increment();
            if (state.equals("55P03")) metrics.counter("payments.lock.timeouts").increment();
            if (attempt < 2) continue;
          }
          if (state != null) throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
          throw failure;
        }
      }
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - begin, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Outcome execute(PaymentCommand p, Caller caller, String correlation) throws SQLException {
    try (Connection c = db.getConnection()) {
      try (var s = c.createStatement()) {
        s.execute("SET LOCAL lock_timeout='2s'");
        s.execute("SET LOCAL statement_timeout='5s'");
      }
      int inserted;
      try (var s =
          c.prepareStatement(
              "INSERT INTO"
                  + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,rejection_code,requested_by,correlation_id)"
                  + " VALUES (?,?,?,?,'JOD',?,?,?,?,'REJECTED','REGISTERING',?,?) ON"
                  + " CONFLICT(payment_id) DO NOTHING")) {
        s.setObject(1, p.paymentId());
        s.setString(2, p.fromAccount());
        s.setString(3, p.toAccount());
        s.setBigDecimal(4, p.amount());
        s.setTimestamp(5, Timestamp.from(p.date()));
        s.setString(6, p.purpose());
        s.setString(7, p.notes());
        s.setString(8, p.fingerprint());
        s.setString(9, caller.subject());
        s.setString(10, correlation);
        inserted = s.executeUpdate();
      }
      try (var s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=? FOR UPDATE")) {
        s.setObject(1, p.paymentId());
        try (var r = s.executeQuery()) {
          r.next();
          if (!r.getString("requested_by").equals(caller.subject()))
            throw new ApiException(403, "FORBIDDEN");
          if (!r.getString("payload_fingerprint").equals(p.fingerprint()))
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
          if (inserted == 0) return readOutcome(r, true);
        }
      }
      Map<String, Account> accounts = store.lock(c, p.fromAccount(), p.toAccount());
      Account from = accounts.get(p.fromAccount()), to = accounts.get(p.toAccount());
      String code = null;
      int status = 409;
      boolean permitted = store.mayDebit(c, from, caller.subject());
      if (!permitted || to == null || !to.incoming()) {
        code = "FORBIDDEN";
        status = 403;
      } else if (!from.status().equals("ACTIVE") || !to.status().equals("ACTIVE")) {
        code = "ACCOUNT_NOT_ACTIVE";
      } else if (!from.currency().equals("JOD") || !to.currency().equals("JOD")) {
        code = "FORBIDDEN";
        status = 403;
      } else if (p.amount().compareTo(from.limit()) > 0
          || to.balance().add(p.amount()).precision() > 19) {
        code = "PAYMENT_LIMIT_EXCEEDED";
      } else if (from.balance().compareTo(p.amount()) < 0) {
        code = "INSUFFICIENT_FUNDS";
      }
      if (code == null) {
        store.updateBalance(c, from.id(), from.balance().subtract(p.amount()));
        probe.afterDebit();
        store.updateBalance(c, to.id(), to.balance().add(p.amount()));
        store.ledger(
            c,
            p.paymentId(),
            from.id(),
            "DEBIT",
            p.amount().negate(),
            from.balance().subtract(p.amount()));
        store.ledger(c, p.paymentId(), to.id(), "CREDIT", p.amount(), to.balance().add(p.amount()));
        status = 201;
      }
      try (var s =
          c.prepareStatement(
              "UPDATE payments SET"
                  + " status=?,rejection_code=?,http_status=?,processed_at=clock_timestamp() WHERE"
                  + " payment_id=?")) {
        s.setString(1, code == null ? "COMPLETED" : "REJECTED");
        s.setString(2, code);
        s.setInt(3, status);
        s.setObject(4, p.paymentId());
        s.executeUpdate();
      }
      try (var s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
        s.setObject(1, p.paymentId());
        try (var r = s.executeQuery()) {
          r.next();
          return readOutcome(r, false);
        }
      }
    }
  }

  private Outcome readOutcome(ResultSet r, boolean replay) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paymentId", r.getObject("payment_id").toString());
    String status = r.getString("status");
    body.put("status", status);
    if (status.equals("COMPLETED")) {
      body.put("fromAccount", r.getString("from_account_id"));
      body.put("toAccount", r.getString("to_account_id"));
      body.put("amount", r.getBigDecimal("amount").setScale(3).toPlainString());
      body.put("currency", "JOD");
      body.put("processedAt", r.getTimestamp("processed_at").toInstant().toString());
    } else {
      body.put("code", r.getString("rejection_code"));
      body.put("message", "The payment could not be completed.");
      body.put(
          "issues",
          List.of(
              new Issue(
                  "payment",
                  r.getString("rejection_code"),
                  "The payment could not be completed.")));
      body.put("correlationId", r.getString("correlation_id"));
      body.put("timestamp", r.getTimestamp("processed_at").toInstant().toString());
    }
    return new Outcome(
        replay && status.equals("COMPLETED") ? 200 : r.getInt("http_status"),
        Collections.unmodifiableMap(body));
  }

  public Outcome find(UUID id, Caller caller) {
    caller.require("payments:read");
    try (var c = db.getConnection();
        var s =
            c.prepareStatement("SELECT * FROM payments WHERE payment_id=? AND requested_by=?")) {
      s.setObject(1, id);
      s.setString(2, caller.subject());
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ApiException(404, "NOT_FOUND");
        return new Outcome(200, readOutcome(r, true).body());
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, Caller caller) {
    caller.require("payments:read");
    try (var c = db.getConnection();
        var s =
            c.prepareStatement(
                "SELECT available_balance FROM accounts WHERE id=? AND (customer_id=? OR"
                    + " EXISTS(SELECT 1 FROM account_debit_grants g WHERE g.account_id=accounts.id"
                    + " AND g.principal=?))")) {
      s.setString(1, id);
      s.setString(2, caller.subject());
      s.setString(3, caller.subject());
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ApiException(404, "NOT_FOUND");
        return Map.of(
            "accountId",
            id,
            "availableBalance",
            r.getBigDecimal(1).setScale(3).toPlainString(),
            "currency",
            "JOD");
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public List<Map<String, Object>> reconcile() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              try (var c = db.getConnection();
                  var s = c.createStatement()) {
                s.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                s.execute("SET LOCAL statement_timeout='10s'");
                List<Map<String, Object>> issues = new ArrayList<>();
                try (var r =
                    s.executeQuery(
                        "SELECT p.payment_id::text id FROM payments p LEFT JOIN ledger_entries l"
                            + " USING(payment_id) WHERE p.status='COMPLETED' GROUP BY p.payment_id"
                            + " HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR"
                            + " count(*) FILTER(WHERE l.entry_type='DEBIT' AND"
                            + " l.account_id=p.from_account_id AND l.signed_amount=-p.amount)<>1 OR"
                            + " count(*) FILTER(WHERE l.entry_type='CREDIT' AND"
                            + " l.account_id=p.to_account_id AND l.signed_amount=p.amount)<>1")) {
                  while (r.next())
                    issues.add(Map.of("type", "PAYMENT_LEDGER_MISMATCH", "id", r.getString(1)));
                }
                try (var r =
                    s.executeQuery(
                        "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON"
                            + " l.account_id=a.id GROUP BY a.id HAVING"
                            + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
                  while (r.next())
                    issues.add(Map.of("type", "ACCOUNT_BALANCE_MISMATCH", "id", r.getString(1)));
                }
                return issues;
              } catch (SQLException e) {
                throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
              }
            });
  }

  private String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof SQLException s) return s.getSQLState();
    return null;
  }
}
