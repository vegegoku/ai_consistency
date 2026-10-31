package com.example.payments.application;

import com.example.payments.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class PaymentService {
  public record Result(int http, Map<String, Object> body) {}

  private final DataSource ds;
  private final MeterRegistry metrics;
  private final TransferHook hook;

  @Inject
  public PaymentService(DataSource ds, MeterRegistry metrics) {
    this(ds, metrics, () -> {});
  }

  public PaymentService(DataSource ds, MeterRegistry metrics, TransferHook hook) {
    this.ds = ds;
    this.metrics = metrics;
    this.hook = hook;
  }

  public Result transfer(PaymentCommand cmd, String actor, String correlation) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          return transaction(cmd, actor, correlation);
        } catch (SQLException e) {
          if (Set.of("40001", "40P01", "55P03", "57014")
              .contains(Objects.toString(e.getSQLState(), ""))) {
            safeMetric("payments.db.lock_timeouts", null);
            if (attempt < 2) {
              safeMetric("payments.retries", null);
              continue;
            }
          }
          throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE")
              .identified(cmd.paymentId().toString());
        }
      }
      throw new IllegalStateException();
    } finally {
      try {
        metrics
            .timer("payments.processing.latency")
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Result transaction(PaymentCommand cmd, String actor, String correlation)
      throws SQLException {
    try (var c = ds.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (var s = c.createStatement()) {
          s.execute("SET LOCAL lock_timeout='2s'");
          s.execute("SET LOCAL statement_timeout='5s'");
        }
        // A transaction-scoped lock serializes a logical ID across stateless replicas; PK is the
        // independent database guard.
        try (var p = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
          p.setLong(
              1,
              cmd.paymentId().getMostSignificantBits() ^ cmd.paymentId().getLeastSignificantBits());
          p.execute();
        }
        try (var p = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
          p.setObject(1, cmd.paymentId());
          try (var r = p.executeQuery()) {
            if (r.next()) {
              if (!actor.equals(r.getString("requested_by"))) {
                c.rollback();
                return failure(403, "FORBIDDEN", cmd.paymentId().toString());
              }
              if (!cmd.fingerprint().equals(r.getString("payload_fingerprint"))) {
                c.rollback();
                return failure(409, "IDEMPOTENCY_CONFLICT", cmd.paymentId().toString());
              }
              var result = stored(r, true);
              c.commit();
              safeMetric("payments.idempotent_replays", null);
              return result;
            }
          }
        }
        try (var p =
            c.prepareStatement(
                "INSERT INTO"
                    + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                    + " VALUES (?,?,?,?,'JOD',?,?,?,?, 'PROCESSING',?,?::uuid)")) {
          p.setObject(1, cmd.paymentId());
          p.setString(2, cmd.fromAccount());
          p.setString(3, cmd.toAccount());
          p.setBigDecimal(4, cmd.amount());
          p.setTimestamp(5, Timestamp.from(cmd.date()));
          p.setString(6, cmd.purpose());
          p.setString(7, cmd.notes());
          p.setString(8, cmd.fingerprint());
          p.setString(9, actor);
          p.setString(10, correlation);
          p.executeUpdate();
        }
        var rows = new HashMap<String, Account>();
        try (var p =
            c.prepareStatement("SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
          p.setString(1, cmd.fromAccount());
          p.setString(2, cmd.toAccount());
          try (var r = p.executeQuery()) {
            while (r.next())
              rows.put(
                  r.getString("id"),
                  new Account(
                      r.getString("id"),
                      r.getString("customer_id"),
                      r.getString("status"),
                      r.getString("currency"),
                      r.getBigDecimal("available_balance"),
                      r.getBigDecimal("transfer_limit"),
                      r.getBoolean("public_receive"),
                      r.getBoolean("receive_enabled")));
          }
        }
        Account from = rows.get(cmd.fromAccount()), to = rows.get(cmd.toAccount());
        String rejection = null;
        int http = 409;
        if (from == null
            || to == null
            || !allowed(c, from, actor, "can_debit")
            || !(to.publicReceive || allowed(c, to, actor, "can_receive"))) {
          rejection = "FORBIDDEN";
          http = 403;
        } else if (!from.status.equals("ACTIVE")
            || !to.status.equals("ACTIVE")
            || !to.receiveEnabled
            || !from.currency.equals("JOD")
            || !to.currency.equals("JOD")) rejection = "ACCOUNT_NOT_ACTIVE";
        else if (cmd.amount().compareTo(from.limit) > 0 || cmd.amount().compareTo(to.limit) > 0)
          rejection = "PAYMENT_LIMIT_EXCEEDED";
        else if (to.balance.add(cmd.amount()).compareTo(new BigDecimal("9999999999999999.999")) > 0)
          rejection = "PAYMENT_LIMIT_EXCEEDED";
        else if (from.balance.compareTo(cmd.amount()) < 0) rejection = "INSUFFICIENT_FUNDS";
        Instant processed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (rejection != null) {
          finish(c, cmd.paymentId(), "REJECTED", rejection, http, processed);
          var rejected = failure(http, rejection, cmd.paymentId().toString());
          var rejectionBody = new LinkedHashMap<>(rejected.body());
          rejectionBody.put("correlationId", correlation);
          rejectionBody.put("timestamp", processed.toString());
          c.commit();
          safeMetric("payments.rejected", rejection);
          return new Result(http, rejectionBody);
        }
        BigDecimal debit = from.balance.subtract(cmd.amount()),
            credit = to.balance.add(cmd.amount());
        updateBalance(c, from.id, debit);
        hook.afterDebit();
        updateBalance(c, to.id, credit);
        ledger(c, cmd.paymentId(), from.id, "DEBIT", cmd.amount().negate(), debit);
        ledger(c, cmd.paymentId(), to.id, "CREDIT", cmd.amount(), credit);
        finish(c, cmd.paymentId(), "COMPLETED", null, null, processed);
        var body = receipt(cmd.paymentId().toString(), from.id, to.id, cmd.amount(), processed);
        c.commit();
        safeMetric("payments.completed", null);
        return new Result(201, body);
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
        }
        throw e;
      }
    }
  }

  private record Account(
      String id,
      String owner,
      String status,
      String currency,
      BigDecimal balance,
      BigDecimal limit,
      boolean publicReceive,
      boolean receiveEnabled) {}

  private boolean allowed(Connection c, Account account, String actor, String permission)
      throws SQLException {
    if (account.owner.equals(actor)) return true;
    String sql =
        permission.equals("can_debit")
            ? "SELECT can_debit FROM account_permissions WHERE account_id=? AND principal=?"
            : "SELECT can_receive FROM account_permissions WHERE account_id=? AND principal=?";
    try (var p = c.prepareStatement(sql)) {
      p.setString(1, account.id);
      p.setString(2, actor);
      try (var r = p.executeQuery()) {
        return r.next() && r.getBoolean(1);
      }
    }
  }

  private void updateBalance(Connection c, String account, BigDecimal amount) throws SQLException {
    try (var p =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                + " id=?")) {
      p.setBigDecimal(1, amount);
      p.setString(2, account);
      if (p.executeUpdate() != 1) throw new SQLException("account disappeared");
    }
  }

  private void ledger(
      Connection c, UUID id, String account, String type, BigDecimal signed, BigDecimal after)
      throws SQLException {
    try (var p =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                + " VALUES (?,?,?,?,?)")) {
      p.setObject(1, id);
      p.setString(2, account);
      p.setString(3, type);
      p.setBigDecimal(4, signed);
      p.setBigDecimal(5, after);
      p.executeUpdate();
    }
  }

  private void finish(Connection c, UUID id, String status, String code, Integer http, Instant time)
      throws SQLException {
    try (var p =
        c.prepareStatement(
            "UPDATE payments SET status=?,rejection_code=?,rejection_http=?,processed_at=? WHERE"
                + " payment_id=?")) {
      p.setString(1, status);
      p.setString(2, code);
      p.setObject(3, http);
      p.setTimestamp(4, Timestamp.from(time));
      p.setObject(5, id);
      p.executeUpdate();
    }
  }

  private Map<String, Object> receipt(
      String id, String from, String to, BigDecimal amount, Instant time) {
    return Map.of(
        "paymentId",
        id,
        "status",
        "COMPLETED",
        "fromAccount",
        from,
        "toAccount",
        to,
        "amount",
        amount.setScale(3).toPlainString(),
        "currency",
        "JOD",
        "processedAt",
        time.toString());
  }

  private Result stored(ResultSet r, boolean replay) throws SQLException {
    if (r.getString("status").equals("REJECTED")) {
      var f =
          failure(
              r.getInt("rejection_http"), r.getString("rejection_code"), r.getString("payment_id"));
      var b = new LinkedHashMap<>(f.body());
      b.put("correlationId", r.getString("correlation_id"));
      b.put("timestamp", r.getTimestamp("processed_at").toInstant().toString());
      return new Result(f.http(), b);
    }
    return new Result(
        replay ? 200 : 201,
        receipt(
            r.getString("payment_id"),
            r.getString("from_account_id"),
            r.getString("to_account_id"),
            r.getBigDecimal("amount"),
            r.getTimestamp("processed_at").toInstant()));
  }

  private void safeMetric(String name, String code) {
    try {
      if (code == null) metrics.counter(name).increment();
      else metrics.counter(name, "code", code).increment();
    } catch (RuntimeException ignored) {
    }
  }

  private Result failure(int http, String code, String id) {
    return new Result(
        http,
        Map.of(
            "paymentId",
            id,
            "status",
            "REJECTED",
            "code",
            code,
            "message",
            "The payment could not be completed.",
            "issues",
            List.of()));
  }

  public Result lookup(UUID id, String actor) {
    try (var c = ds.getConnection();
        var p =
            c.prepareStatement("SELECT * FROM payments WHERE payment_id=? AND requested_by=?")) {
      p.setObject(1, id);
      p.setString(2, actor);
      try (var r = p.executeQuery()) {
        if (!r.next()) throw new ApiFailure(404, "PAYMENT_NOT_FOUND");
        return new Result(200, stored(r, true).body());
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, String actor) {
    try (var c = ds.getConnection();
        var p =
            c.prepareStatement(
                "SELECT available_balance FROM accounts a WHERE a.id=? AND (a.customer_id=? OR"
                    + " EXISTS(SELECT 1 FROM account_permissions p WHERE p.account_id=a.id AND"
                    + " p.principal=? AND p.can_debit))")) {
      p.setString(1, id);
      p.setString(2, actor);
      p.setString(3, actor);
      try (var r = p.executeQuery()) {
        if (!r.next()) throw new ApiFailure(404, "ACCOUNT_NOT_FOUND");
        return Map.of(
            "accountId",
            id,
            "balance",
            r.getBigDecimal(1).setScale(3).toPlainString(),
            "currency",
            "JOD");
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public void rateLimit(String actor, int max) {
    try (var c = ds.getConnection();
        var p =
            c.prepareStatement(
                "INSERT INTO rate_windows(principal,window_start,request_count) VALUES (?,?,1) ON"
                    + " CONFLICT(principal) DO UPDATE SET"
                    + " window_start=excluded.window_start,request_count=CASE WHEN"
                    + " rate_windows.window_start=excluded.window_start THEN"
                    + " rate_windows.request_count+1 ELSE 1 END RETURNING request_count")) {
      p.setString(1, actor);
      p.setLong(2, Instant.now().getEpochSecond() / 60);
      try (var r = p.executeQuery()) {
        r.next();
        if (r.getInt(1) > max) throw new ApiFailure(429, "RATE_LIMITED");
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
