package com.example.payments.application;

import com.example.payments.api.Codec;
import com.example.payments.domain.*;
import com.example.payments.observability.Telemetry;
import com.example.payments.persistence.Database;
import com.example.payments.security.Caller;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class PaymentService {
  public record Outcome(int http, Map<String, Object> body, boolean replay) {}

  @FunctionalInterface
  public interface TransactionProbe {
    void afterDebit(Connection c) throws SQLException;
  }

  private record Account(
      String id,
      String owner,
      BigDecimal balance,
      String status,
      boolean receiving,
      boolean unrestricted,
      BigDecimal dailyLimit,
      String customerStatus) {}

  private final Database db;
  private final Codec codec;
  private final Telemetry telemetry;
  private final TransactionProbe probe;

  public PaymentService(Database db, Codec codec, Telemetry telemetry) {
    this(db, codec, telemetry, c -> {});
  }

  public PaymentService(Database db, Codec codec, Telemetry telemetry, TransactionProbe probe) {
    this.db = db;
    this.codec = codec;
    this.telemetry = telemetry;
    this.probe = probe;
  }

  public Outcome transfer(PaymentCommand cmd, Caller caller, String correlation) {
    if (!caller.permits("payments:submit")) throw new ApiException(403, "FORBIDDEN");
    for (int attempt = 0; attempt < 3; attempt++) {
      try (var c = db.pool.getConnection()) {
        c.setAutoCommit(false);
        try {
          try (var s = c.createStatement()) {
            s.execute("SET LOCAL lock_timeout='1500ms'");
            s.execute("SET LOCAL statement_timeout='4000ms'");
          }
          try (var p = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            p.setString(1, UUID.fromString(cmd.paymentId()).toString());
            p.execute();
          }
          Outcome existing = existing(c, cmd, caller);
          if (existing != null) {
            c.commit();
            safeMetric("payments.replays", "REPLAY");
            return existing;
          }
          codec.validateNewDate(cmd);
          Map<String, Account> accounts = new HashMap<>();
          try (var p =
              c.prepareStatement(
                  "SELECT a.*,c.status AS customer_status FROM accounts a JOIN customers c ON"
                      + " c.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a")) {
            p.setString(1, cmd.fromAccount());
            p.setString(2, cmd.toAccount());
            try (var r = p.executeQuery()) {
              while (r.next())
                accounts.put(
                    r.getString("id"),
                    new Account(
                        r.getString("id"),
                        r.getString("customer_id"),
                        r.getBigDecimal("available_balance"),
                        r.getString("status"),
                        r.getBoolean("receive_allowed"),
                        r.getBoolean("unrestricted_destinations"),
                        r.getBigDecimal("daily_debit_limit"),
                        r.getString("customer_status")));
            }
          }
          Account from = accounts.get(cmd.fromAccount()), to = accounts.get(cmd.toAccount());
          String rejection = null;
          int http = 409;
          if (from == null
              || !authorized(c, from.id(), from.owner(), caller)
              || to == null
              || !to.receiving()) {
            rejection = "FORBIDDEN";
            http = 403;
          } else if (!from.status().equals("ACTIVE")
              || !to.status().equals("ACTIVE")
              || !from.customerStatus().equals("ACTIVE")
              || !to.customerStatus().equals("ACTIVE")) rejection = "ACCOUNT_NOT_ACTIVE";
          else if (!from.unrestricted()
              && !exists(
                  c,
                  "SELECT 1 FROM allowed_destinations WHERE from_account_id=? AND to_account_id=?",
                  from.id(),
                  to.id())) {
            rejection = "FORBIDDEN";
            http = 403;
          } else if (from.balance().compareTo(cmd.amount()) < 0) rejection = "INSUFFICIENT_FUNDS";
          else if (dailyTotal(c, from.id()).add(cmd.amount()).compareTo(from.dailyLimit()) > 0
              || to.balance().add(cmd.amount()).compareTo(new BigDecimal("9999999999999999.999"))
                  > 0) rejection = "PAYMENT_LIMIT_EXCEEDED";
          String now = Instant.now().toString();
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("paymentId", cmd.paymentId());
          body.put("status", rejection == null ? "COMPLETED" : "REJECTED");
          if (rejection == null) {
            body.put("fromAccount", cmd.fromAccount());
            body.put("toAccount", cmd.toAccount());
            body.put("amount", cmd.amount().toPlainString());
            body.put("currency", "JOD");
            body.put("processedAt", now);
            http = 201;
          } else {
            body.put("code", rejection);
            body.put("message", "The payment could not be completed.");
            body.put(
                "issues",
                List.of(
                    Map.of(
                        "field",
                        rejection.equals("INSUFFICIENT_FUNDS") ? "amount" : "payment",
                        "code",
                        rejection,
                        "message",
                        rejection.equals("INSUFFICIENT_FUNDS")
                            ? "Available funds are insufficient."
                            : "The payment could not be completed.")));
            body.put("correlationId", correlation);
            body.put("timestamp", now);
          }
          insertPayment(c, cmd, caller, correlation, now, rejection, http, body);
          if (rejection == null) {
            BigDecimal debit = from.balance().subtract(cmd.amount()),
                credit = to.balance().add(cmd.amount());
            updateBalance(c, from.id(), debit);
            probe.afterDebit(c);
            updateBalance(c, to.id(), credit);
            ledger(c, cmd.paymentId(), from.id(), "DEBIT", cmd.amount().negate(), debit);
            ledger(c, cmd.paymentId(), to.id(), "CREDIT", cmd.amount(), credit);
          } else
            try (var p =
                c.prepareStatement(
                    "INSERT INTO payment_issues(payment_id,code,field,safe_message,correlation_id)"
                        + " VALUES(?,?,'payment','The payment could not be completed.',?)")) {
              p.setObject(1, UUID.fromString(cmd.paymentId()));
              p.setString(2, rejection);
              p.setObject(3, UUID.fromString(correlation));
              p.executeUpdate();
            }
          c.commit();
          safeMetric(
              rejection == null ? "payments.completed" : "payments.rejected",
              rejection == null ? "COMPLETED" : rejection);
          return new Outcome(http, body, false);
        } catch (SQLException | RuntimeException e) {
          try {
            c.rollback();
          } catch (SQLException ignored) {
          }
          throw e;
        }
      } catch (SQLException e) {
        String state = e.getSQLState();
        boolean retry =
            Set.of("40001", "40P01", "55P03", "57014").contains(state == null ? "" : state);
        if (retry) {
          safeMetric("payments.lock.timeouts", state);
          if (attempt < 2) {
            safeMetric("payments.retries", state);
            try {
              Thread.sleep(25L * (attempt + 1));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
            }
            continue;
          }
        }
        if ((state == null
            || e instanceof SQLTransientConnectionException
            || state.startsWith("08")
            || retry
            || state.equals("53300")
            || state.equals("57P01"))) throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
        safeMetric("payments.failures", "INTERNAL_ERROR");
        throw new ApiException(500, "INTERNAL_ERROR");
      }
    }
    throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
  }

  private Outcome existing(Connection c, PaymentCommand cmd, Caller caller) throws SQLException {
    try (var p =
        c.prepareStatement(
            "SELECT requested_by,payload_fingerprint,http_status,receipt::text FROM payments WHERE"
                + " payment_id=?")) {
      p.setObject(1, UUID.fromString(cmd.paymentId()));
      try (var r = p.executeQuery()) {
        if (!r.next()) return null;
        if (!caller.subject().equals(r.getString(1))) throw new ApiException(403, "FORBIDDEN");
        if (!cmd.fingerprint().equals(r.getString(2)))
          throw ApiException.business(409, "IDEMPOTENCY_CONFLICT", cmd.paymentId());
        int status = r.getInt(3);
        return new Outcome(status == 201 ? 200 : status, readBody(r.getString(4)), true);
      }
    }
  }

  private BigDecimal dailyTotal(Connection c, String id) throws SQLException {
    try (var p =
        c.prepareStatement(
            "SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND"
                + " status='COMPLETED' AND processed_at>=date_trunc('day',now())")) {
      p.setString(1, id);
      try (var r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  private boolean authorized(Connection c, String id, String owner, Caller caller)
      throws SQLException {
    return Objects.equals(owner, caller.subject())
        || exists(
            c,
            "SELECT 1 FROM account_debit_grants WHERE account_id=? AND principal=?",
            id,
            caller.subject());
  }

  private boolean exists(Connection c, String sql, String a, String b) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      p.setString(1, a);
      p.setString(2, b);
      try (var r = p.executeQuery()) {
        return r.next();
      }
    }
  }

  private void updateBalance(Connection c, String id, BigDecimal value) throws SQLException {
    try (var p =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                + " id=?")) {
      p.setBigDecimal(1, value);
      p.setString(2, id);
      if (p.executeUpdate() != 1) throw new SQLException("Missing locked account");
    }
  }

  private void ledger(
      Connection c, String id, String account, String type, BigDecimal amount, BigDecimal after)
      throws SQLException {
    try (var p =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                + " VALUES(?,?,?,?,?)")) {
      p.setObject(1, UUID.fromString(id));
      p.setString(2, account);
      p.setString(3, type);
      p.setBigDecimal(4, amount);
      p.setBigDecimal(5, after);
      p.executeUpdate();
    }
  }

  private void insertPayment(
      Connection c,
      PaymentCommand cmd,
      Caller caller,
      String correlation,
      String now,
      String rejection,
      int http,
      Map<String, Object> body)
      throws SQLException {
    try (var p =
        c.prepareStatement(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,rejection_code,processed_at,requested_by,correlation_id,http_status,receipt)"
                + " VALUES(?,?,?,?,'JOD',?,?,?,?,?,?,?,?,?,?,?::jsonb)")) {
      p.setObject(1, UUID.fromString(cmd.paymentId()));
      p.setString(2, cmd.fromAccount());
      p.setString(3, cmd.toAccount());
      p.setBigDecimal(4, cmd.amount());
      p.setTimestamp(5, Timestamp.from(cmd.date()));
      p.setString(6, cmd.purpose());
      p.setString(7, cmd.notes());
      p.setString(8, cmd.fingerprint());
      p.setString(9, rejection == null ? "COMPLETED" : "REJECTED");
      p.setString(10, rejection);
      p.setTimestamp(11, Timestamp.from(Instant.parse(now)));
      p.setString(12, caller.subject());
      p.setObject(13, UUID.fromString(correlation));
      p.setInt(14, http);
      try {
        p.setString(15, Codec.JSON.writeValueAsString(body));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      p.executeUpdate();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readBody(String text) {
    try {
      return Codec.JSON.readValue(text, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> payment(String id, Caller caller) {
    if (!caller.permits("payments:read")) throw new ApiException(403, "FORBIDDEN");
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiException(404, "NOT_FOUND");
    }
    try (var c = db.pool.getConnection();
        var p =
            c.prepareStatement(
                "SELECT p.receipt::text,p.requested_by,a.customer_id,p.from_account_id FROM"
                    + " payments p LEFT JOIN accounts a ON a.id=p.from_account_id WHERE"
                    + " payment_id=?")) {
      p.setObject(1, uuid);
      try (var r = p.executeQuery()) {
        if (!r.next()
            || !r.getString(2).equals(caller.subject())
                && !authorized(c, r.getString(4), r.getString(3), caller))
          throw new ApiException(404, "NOT_FOUND");
        return readBody(r.getString(1));
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, Caller caller) {
    if (!caller.permits("payments:read")) throw new ApiException(403, "FORBIDDEN");
    try (var c = db.pool.getConnection();
        var p =
            c.prepareStatement("SELECT customer_id,available_balance FROM accounts WHERE id=?")) {
      p.setString(1, id);
      try (var r = p.executeQuery()) {
        if (!r.next() || !authorized(c, id, r.getString(1), caller))
          throw new ApiException(404, "NOT_FOUND");
        return Map.of(
            "accountId",
            id,
            "balance",
            r.getBigDecimal(2).setScale(3).toPlainString(),
            "currency",
            "JOD");
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public List<Map<String, Object>> reconcile() {
    List<Map<String, Object>> failures = new ArrayList<>();
    try (var c = db.pool.getConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      c.setReadOnly(true);
      try (var s = c.createStatement()) {
        s.setQueryTimeout(10);
        try (var r =
            s.executeQuery(
                "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                    + " l.payment_id=p.payment_id GROUP BY p.payment_id HAVING"
                    + " (p.status='COMPLETED' AND (count(l.id)<>2 OR"
                    + " coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE"
                    + " (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                    + " l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND"
                    + " l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR"
                    + " (p.status='REJECTED' AND count(l.id)<>0)")) {
          while (r.next())
            failures.add(Map.of("type", "PAYMENT_LEDGER_MISMATCH", "paymentId", r.getString(1)));
        }
        try (var r =
            s.executeQuery(
                "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP"
                    + " BY a.id HAVING"
                    + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
          while (r.next())
            failures.add(Map.of("type", "ACCOUNT_BALANCE_MISMATCH", "accountId", r.getString(1)));
        }
        try (var r =
            s.executeQuery(
                "SELECT account_id FROM (SELECT l.*,a.opening_balance+sum(l.signed_amount)"
                    + " OVER(PARTITION BY l.account_id ORDER BY l.id) AS derived FROM"
                    + " ledger_entries l JOIN accounts a ON a.id=l.account_id) s WHERE"
                    + " balance_after<>derived")) {
          while (r.next())
            failures.add(
                Map.of("type", "LEDGER_BALANCE_AFTER_MISMATCH", "accountId", r.getString(1)));
        }
      }
      c.commit();
      return failures;
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  private void safeMetric(String metric, String code) {
    try {
      telemetry.count(metric, code);
    } catch (RuntimeException ignored) {
    }
  }
}
