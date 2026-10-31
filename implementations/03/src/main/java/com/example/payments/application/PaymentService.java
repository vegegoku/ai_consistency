package com.example.payments.application;

import com.example.payments.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class PaymentService {
  @Inject DataSource dataSource;
  @Inject MeterRegistry metrics;

  public PaymentService() {}

  public PaymentService(DataSource ds) {
    this.dataSource = ds;
    this.metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  }

  public record Result(int http, Map<String, Object> receipt, boolean replay) {}

  record Account(
      String id,
      String owner,
      String status,
      String currency,
      BigDecimal balance,
      boolean receive,
      BigDecimal limit) {}

  public Result transfer(PaymentCommand p, String principal, String correlation) {
    long started = System.nanoTime();
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        try (Connection c = dataSource.getConnection()) {
          c.setAutoCommit(false);
          try {
            try (var s = c.createStatement()) {
              s.execute("SET LOCAL lock_timeout='2s'");
              s.execute("SET LOCAL statement_timeout='5s'");
            }
            int inserted;
            try (var s =
                c.prepareStatement(
                    "INSERT INTO"
                        + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?) ON CONFLICT DO NOTHING")) {
              s.setObject(1, p.paymentId());
              s.setString(2, p.fromAccount());
              s.setString(3, p.toAccount());
              s.setBigDecimal(4, p.amount());
              s.setTimestamp(5, Timestamp.from(p.date()));
              s.setString(6, p.purpose());
              s.setString(7, p.notes());
              s.setString(8, p.fingerprint());
              s.setString(9, principal);
              s.setObject(10, UUID.fromString(correlation));
              inserted = s.executeUpdate();
            }
            if (inserted == 0) {
              Result r = stored(c, p.paymentId(), principal, p.fingerprint());
              c.commit();
              metric("payments.replays");
              return r;
            }
            var accounts = new HashMap<String, Account>();
            try (var s =
                c.prepareStatement(
                    "SELECT"
                        + " id,customer_id,status,currency,available_balance,receive_allowed,transfer_limit"
                        + " FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
              s.setString(1, p.fromAccount());
              s.setString(2, p.toAccount());
              try (var rows = s.executeQuery()) {
                while (rows.next())
                  accounts.put(
                      rows.getString(1),
                      new Account(
                          rows.getString(1),
                          rows.getString(2),
                          rows.getString(3),
                          rows.getString(4),
                          rows.getBigDecimal(5),
                          rows.getBoolean(6),
                          rows.getBigDecimal(7)));
              }
            }
            Account from = accounts.get(p.fromAccount()), to = accounts.get(p.toAccount());
            String reject = null;
            int http = 409;
            if (from == null || !permitted(c, from, principal, true)) {
              reject = "FORBIDDEN";
              http = 403;
            } else if (to == null) {
              reject = "FORBIDDEN";
              http = 403;
            } else if (!permitted(c, to, principal, false) || !to.receive) {
              reject = "FORBIDDEN";
              http = 403;
            } else if (!from.status.equals("ACTIVE")
                || !to.status.equals("ACTIVE")
                || !from.currency.equals("JOD")
                || !to.currency.equals("JOD")) reject = "ACCOUNT_NOT_ACTIVE";
            else if (p.amount().compareTo(from.limit) > 0 || p.amount().compareTo(to.limit) > 0)
              reject = "PAYMENT_LIMIT_EXCEEDED";
            else if (from.balance.compareTo(p.amount()) < 0) reject = "INSUFFICIENT_FUNDS";
            if (reject != null) {
              finish(c, p.paymentId(), "REJECTED", reject, http);
              Result r = stored(c, p.paymentId(), principal, null);
              c.commit();
              metric("payments.rejected", "code", reject);
              return new Result(http, r.receipt, false);
            }
            balance(c, from.id, from.balance.subtract(p.amount()));
            balance(c, to.id, to.balance.add(p.amount()));
            ledger(
                c,
                p.paymentId(),
                from.id,
                "DEBIT",
                p.amount().negate(),
                from.balance.subtract(p.amount()));
            ledger(c, p.paymentId(), to.id, "CREDIT", p.amount(), to.balance.add(p.amount()));
            finish(c, p.paymentId(), "COMPLETED", null, 201);
            Result r = stored(c, p.paymentId(), principal, null);
            c.commit();
            metric("payments.completed");
            return new Result(201, r.receipt, false);
          } catch (Exception e) {
            c.rollback();
            throw e;
          }
        } catch (SQLException e) {
          String state = e.getSQLState();
          boolean retry =
              Set.of("40P01", "40001", "55P03", "57014").contains(state == null ? "" : state);
          if (retry) {
            metric("payments.lock.timeouts");
            if (attempt < 2) {
              metric("payments.retries");
              try {
                Thread.sleep(25L * (attempt + 1));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
              }
              continue;
            }
          }
          throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE").payment(p.paymentId().toString());
        }
      }
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException telemetryFailure) {
        /* Telemetry cannot alter a financial outcome. */
      }
    }
  }

  private void metric(String name, String... tags) {
    try {
      metrics.counter(name, tags).increment();
    } catch (RuntimeException telemetryFailure) {
      /* Committed financial results remain authoritative. */
    }
  }

  private boolean permitted(Connection c, Account a, String principal, boolean debit)
      throws SQLException {
    if (a.owner.equals(principal)) return true;
    try (var s =
        c.prepareStatement(
            "SELECT "
                + (debit ? "can_debit" : "can_receive")
                + " FROM account_permissions WHERE account_id=? AND principal=?")) {
      s.setString(1, a.id);
      s.setString(2, principal);
      try (var r = s.executeQuery()) {
        return r.next() && r.getBoolean(1);
      }
    }
  }

  private void balance(Connection c, String id, BigDecimal balance) throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                + " id=?")) {
      s.setBigDecimal(1, balance);
      s.setString(2, id);
      s.executeUpdate();
    }
  }

  private void ledger(
      Connection c, UUID id, String account, String type, BigDecimal amount, BigDecimal balance)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                + " VALUES (?,?,?,?,?)")) {
      s.setObject(1, id);
      s.setString(2, account);
      s.setString(3, type);
      s.setBigDecimal(4, amount);
      s.setBigDecimal(5, balance);
      s.executeUpdate();
    }
  }

  private void finish(Connection c, UUID id, String status, String code, int http)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE payments SET status=?,rejection_code=?,rejection_http=?,processed_at=now()"
                + " WHERE payment_id=?")) {
      s.setString(1, status);
      s.setString(2, code);
      s.setInt(3, http);
      s.setObject(4, id);
      s.executeUpdate();
    }
  }

  private Result stored(Connection c, UUID id, String principal, String fingerprint)
      throws SQLException {
    try (var s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
      s.setObject(1, id);
      try (var r = s.executeQuery()) {
        if (!r.next() || !r.getString("requested_by").equals(principal))
          throw new ApiFailure(404, "NOT_FOUND");
        if (fingerprint != null && !fingerprint.equals(r.getString("payload_fingerprint")))
          throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT").payment(id.toString());
        var body = new LinkedHashMap<String, Object>();
        body.put("paymentId", id.toString());
        body.put("status", r.getString("status"));
        if (r.getString("status").equals("REJECTED")) {
          body.put("code", r.getString("rejection_code"));
          body.put("message", "The payment could not be completed.");
          body.put("issues", List.of());
          body.put("correlationId", r.getString("correlation_id"));
          body.put("timestamp", r.getTimestamp("processed_at").toInstant().toString());
          return new Result(r.getInt("rejection_http"), body, true);
        }
        body.put("fromAccount", r.getString("from_account_id"));
        body.put("toAccount", r.getString("to_account_id"));
        body.put("amount", r.getBigDecimal("amount").setScale(3).toPlainString());
        body.put("currency", "JOD");
        body.put("processedAt", r.getTimestamp("processed_at").toInstant().toString());
        return new Result(200, body, true);
      }
    }
  }

  public Result get(UUID id, String principal) {
    try (Connection c = dataSource.getConnection()) {
      return stored(c, id, principal, null);
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, String principal) {
    try (Connection c = dataSource.getConnection();
        var s =
            c.prepareStatement(
                "SELECT a.available_balance FROM accounts a WHERE a.id=? AND (a.customer_id=? OR"
                    + " EXISTS(SELECT 1 FROM account_permissions p WHERE p.account_id=a.id AND"
                    + " p.principal=? AND p.can_debit))")) {
      s.setString(1, id);
      s.setString(2, principal);
      s.setString(3, principal);
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ApiFailure(404, "NOT_FOUND");
        return Map.of(
            "accountId",
            id,
            "availableBalance",
            r.getBigDecimal(1).setScale(3).toPlainString(),
            "currency",
            "JOD");
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> reconcile() {
    try (Connection c = dataSource.getConnection();
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      var badPayments = new ArrayList<String>();
      try (var r =
          s.executeQuery(
              "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                  + " p.payment_id=l.payment_id WHERE p.status='COMPLETED' GROUP BY p.payment_id"
                  + " HAVING count(l.id)<>2 OR sum(l.signed_amount)<>0")) {
        while (r.next()) badPayments.add(r.getString(1));
      }
      var badAccounts = new ArrayList<String>();
      try (var r =
          s.executeQuery(
              "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP BY"
                  + " a.id HAVING"
                  + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (r.next()) badAccounts.add(r.getString(1));
      }
      return Map.of(
          "healthy",
          badPayments.isEmpty() && badAccounts.isEmpty(),
          "unbalancedPayments",
          badPayments,
          "inconsistentAccounts",
          badAccounts,
          "timestamp",
          Instant.now().toString());
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
