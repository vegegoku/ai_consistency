package com.example.payments.application;

import com.example.payments.api.Errors;
import com.example.payments.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Metrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** All financial writes are confined to one explicit PostgreSQL transaction. */
@ApplicationScoped
public class PaymentService {
  private final DataSource source;
  private final ObjectMapper mapper = new ObjectMapper();
  private final int attempts, lockMillis, statementMillis;

  public PaymentService(DataSource source) {
    this(source, 3, 2000, 5000);
  }

  @Inject
  public PaymentService(
      AgroalDataSource source,
      @ConfigProperty(name = "payment.db-attempts", defaultValue = "3") int attempts,
      @ConfigProperty(name = "payment.lock-timeout-ms", defaultValue = "2000") int lockMillis,
      @ConfigProperty(name = "payment.statement-timeout-ms", defaultValue = "5000")
          int statementMillis) {
    this((DataSource) source, attempts, lockMillis, statementMillis);
  }

  private PaymentService(DataSource source, int attempts, int lockMillis, int statementMillis) {
    this.source = source;
    this.attempts = attempts;
    this.lockMillis = lockMillis;
    this.statementMillis = statementMillis;
  }

  public PaymentResult transfer(PaymentCommand command, Caller caller, String correlation) {
    for (int attempt = 0; attempt < attempts; attempt++)
      try {
        return execute(command, caller, correlation);
      } catch (SQLException e) {
        String state = e.getSQLState();
        boolean retry = Set.of("40P01", "40001", "55P03").contains(state == null ? "" : state);
        if ("55P03".equals(state)) metric("payment.lock.timeouts");
        if (!retry || attempt == attempts - 1)
          throw new PaymentException(
              503, "TEMPORARILY_UNAVAILABLE", List.of(), command.paymentId().toString());
        metric("payment.retries");
        try {
          Thread.sleep(20L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
        }
      }
    throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
  }

  private PaymentResult execute(PaymentCommand p, Caller who, String correlation)
      throws SQLException {
    try (var c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (var s = c.createStatement()) {
          s.execute("SET LOCAL lock_timeout='" + Math.max(1, lockMillis) + "ms'");
          s.execute("SET LOCAL statement_timeout='" + Math.max(1, statementMillis) + "ms'");
        }
        int inserted =
            update(
                c,
                "INSERT INTO"
                    + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO"
                    + " NOTHING",
                p.paymentId(),
                p.fromAccount(),
                p.toAccount(),
                p.amount(),
                p.date().atOffset(ZoneOffset.UTC),
                p.purpose(),
                p.notes(),
                p.fingerprint(),
                who.subject(),
                UUID.fromString(correlation));
        if (inserted == 0) {
          PaymentResult result = replay(c, p, who);
          c.commit();
          metric("payment.replays");
          return result;
        }
        Map<String, Account> accounts = new HashMap<>();
        try (var q =
                prepare(
                    c,
                    "SELECT a.*,cu.status AS customer_status FROM accounts a JOIN customers cu ON"
                        + " cu.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a",
                    p.fromAccount(),
                    p.toAccount());
            var r = q.executeQuery()) {
          while (r.next())
            accounts.put(
                r.getString("id"),
                new Account(
                    r.getString("id"),
                    r.getString("customer_id"),
                    r.getString("status"),
                    r.getString("currency"),
                    r.getBigDecimal("available_balance"),
                    r.getBigDecimal("daily_limit"),
                    r.getBoolean("receiving_allowed"),
                    r.getBoolean("public_receive"),
                    r.getString("customer_status")));
        }
        Account from = accounts.get(p.fromAccount()), to = accounts.get(p.toAccount());
        String code = null;
        int http = 409;
        if (from == null || !allowed(c, who, from, "can_debit")) {
          code = "FORBIDDEN";
          http = 403;
        } else if (to == null || (!to.publicReceive && !allowed(c, who, to, "can_receive"))) {
          code = "FORBIDDEN";
          http = 403;
        } else if (!from.status.equals("ACTIVE")
            || !to.status.equals("ACTIVE")
            || !from.customerStatus.equals("ACTIVE")
            || !to.customerStatus.equals("ACTIVE")
            || !to.receivingAllowed
            || !from.currency.equals("JOD")
            || !to.currency.equals("JOD")) code = "ACCOUNT_NOT_ACTIVE";
        else if (from.balance.compareTo(p.amount()) < 0) code = "INSUFFICIENT_FUNDS";
        else if (to.balance.add(p.amount()).precision() > 19
            || dailySpent(c, from.id).add(p.amount()).compareTo(from.dailyLimit) > 0)
          code = "PAYMENT_LIMIT_EXCEEDED";
        Instant processed = Instant.now();
        Map<String, Object> body;
        if (code != null) {
          body =
              Errors.body(
                  new PaymentException(http, code, List.of(), p.paymentId().toString()),
                  correlation);
          body.put("status", "REJECTED");
        } else {
          BigDecimal debit = from.balance.subtract(p.amount()), credit = to.balance.add(p.amount());
          update(
              c,
              "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                  + " id=?",
              debit,
              from.id);
          update(
              c,
              "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                  + " id=?",
              credit,
              to.id);
          update(
              c,
              "INSERT INTO"
                  + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                  + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
              p.paymentId(),
              from.id,
              p.amount().negate(),
              debit,
              p.paymentId(),
              to.id,
              p.amount(),
              credit);
          body = new LinkedHashMap<>();
          body.put("paymentId", p.paymentId().toString());
          body.put("status", "COMPLETED");
          body.put("fromAccount", p.fromAccount());
          body.put("toAccount", p.toAccount());
          body.put("amount", p.amount().toPlainString());
          body.put("currency", "JOD");
          body.put("processedAt", processed.toString());
          http = 201;
        }
        update(
            c,
            "UPDATE payments SET"
                + " status=?,rejection_code=?,processed_at=?,response_body=?,http_status=? WHERE"
                + " payment_id=?",
            code == null ? "COMPLETED" : "REJECTED",
            code,
            processed.atOffset(ZoneOffset.UTC),
            serialize(body),
            http,
            p.paymentId());
        c.commit();
        metric(
            code == null ? "payment.completed" : "payment.rejected", code == null ? "NONE" : code);
        return new PaymentResult(http, false, body);
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
        }
        if (e instanceof SQLException sql) throw sql;
        if (e instanceof PaymentException domain) throw domain;
        throw new SQLException("Transaction failed", "XX000", e);
      }
    }
  }

  private PaymentResult replay(Connection c, PaymentCommand p, Caller who) throws SQLException {
    try (var q =
            prepare(
                c,
                "SELECT requested_by,payload_fingerprint,status,response_body,http_status FROM"
                    + " payments WHERE payment_id=?",
                p.paymentId());
        var r = q.executeQuery()) {
      if (!r.next()) throw new SQLException("Concurrent outcome unavailable", "40001");
      if (!r.getString("requested_by").equals(who.subject()) && !who.administrator())
        throw new PaymentException(403, "FORBIDDEN");
      if (!r.getString("payload_fingerprint").equals(p.fingerprint()))
        throw new PaymentException(
            409, "IDEMPOTENCY_CONFLICT", List.of(), p.paymentId().toString());
      if (r.getString("status").equals("PROCESSING"))
        throw new SQLException("Uncommitted outcome", "40001");
      return new PaymentResult(
          r.getString("status").equals("COMPLETED") ? 200 : r.getInt("http_status"),
          true,
          deserialize(r.getString("response_body")));
    }
  }

  private BigDecimal dailySpent(Connection c, String account) throws SQLException {
    try (var q =
            prepare(
                c,
                "SELECT COALESCE(sum(amount),0) FROM payments WHERE from_account_id=? AND"
                    + " status='COMPLETED' AND processed_at >= date_trunc('day',now() AT TIME ZONE"
                    + " 'UTC') AT TIME ZONE 'UTC'",
                account);
        var r = q.executeQuery()) {
      r.next();
      return r.getBigDecimal(1);
    }
  }

  private boolean allowed(Connection c, Caller who, Account a, String permission)
      throws SQLException {
    if (who.administrator() || a.owner.equals(who.subject())) return true;
    if (!Set.of("can_debit", "can_receive", "can_view").contains(permission))
      throw new IllegalArgumentException();
    try (var q =
            prepare(
                c,
                "SELECT 1 FROM account_permissions WHERE principal=? AND account_id=? AND "
                    + permission,
                who.subject(),
                a.id);
        var r = q.executeQuery()) {
      return r.next();
    }
  }

  public PaymentResult find(UUID id, Caller who) {
    try (var c = source.getConnection();
        var q =
            prepare(
                c,
                "SELECT requested_by,status,response_body FROM payments WHERE payment_id=?",
                id);
        var r = q.executeQuery()) {
      if (!r.next() || (!who.administrator() && !who.subject().equals(r.getString("requested_by"))))
        throw new PaymentException(404, "PAYMENT_NOT_FOUND");
      if (r.getString("status").equals("PROCESSING"))
        throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
      return new PaymentResult(200, false, deserialize(r.getString("response_body")));
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, Caller who) {
    try (var c = source.getConnection();
        var q = prepare(c, "SELECT * FROM accounts WHERE id=?", id);
        var r = q.executeQuery()) {
      if (!r.next()) throw new PaymentException(404, "ACCOUNT_NOT_FOUND");
      var a =
          new Account(
              id,
              r.getString("customer_id"),
              r.getString("status"),
              r.getString("currency"),
              r.getBigDecimal("available_balance"),
              r.getBigDecimal("daily_limit"),
              r.getBoolean("receiving_allowed"),
              r.getBoolean("public_receive"),
              "ACTIVE");
      if (!allowed(c, who, a, "can_view") && !allowed(c, who, a, "can_debit"))
        throw new PaymentException(404, "ACCOUNT_NOT_FOUND");
      return Map.of(
          "accountId", id, "availableBalance", a.balance.toPlainString(), "currency", "JOD");
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  /** Snapshot report; opening balances are the immutable accounting baseline. */
  public List<Map<String, Object>> reconcile() {
    try (var c = source.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      var findings = new ArrayList<Map<String, Object>>();
      try (var q = c.createStatement();
          var r =
              q.executeQuery(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l USING(payment_id)"
                      + " WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id,p.amount,p.from_account_id,p.to_account_id HAVING"
                      + " count(l.id)<>2 OR COALESCE(sum(l.signed_amount),0)<>0 OR count(*)"
                      + " FILTER(WHERE l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                      + " l.signed_amount=-p.amount)<>1 OR count(*) FILTER(WHERE"
                      + " l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND"
                      + " l.signed_amount=p.amount)<>1")) {
        while (r.next())
          findings.add(Map.of("type", "PAYMENT_LEDGER_MISMATCH", "paymentId", r.getString(1)));
      }
      try (var q = c.createStatement();
          var r =
              q.executeQuery(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+COALESCE(sum(l.signed_amount),0)")) {
        while (r.next())
          findings.add(Map.of("type", "ACCOUNT_BALANCE_MISMATCH", "accountId", r.getString(1)));
      }
      c.commit();
      return findings;
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public void rateLimit(Caller caller, int maximum) {
    if (maximum < 1) return;
    try (var c = source.getConnection();
        var q =
            prepare(
                c,
                "INSERT INTO rate_limit_buckets(principal,window_start,request_count)"
                    + " VALUES(?,date_trunc('minute',now()),1) ON CONFLICT(principal) DO UPDATE SET"
                    + " window_start=EXCLUDED.window_start,request_count=CASE WHEN"
                    + " rate_limit_buckets.window_start=EXCLUDED.window_start THEN"
                    + " rate_limit_buckets.request_count+1 ELSE 1 END RETURNING request_count",
                caller.subject());
        var r = q.executeQuery()) {
      r.next();
      if (r.getInt(1) > maximum) throw new PaymentException(429, "RATE_LIMITED");
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  private PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
    var p = c.prepareStatement(sql);
    p.setQueryTimeout(Math.max(1, statementMillis / 1000));
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    return p;
  }

  private int update(Connection c, String sql, Object... args) throws SQLException {
    try (var p = prepare(c, sql, args)) {
      return p.executeUpdate();
    }
  }

  private String serialize(Map<String, Object> body) throws SQLException {
    try {
      return mapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new SQLException("Receipt serialization failed", e);
    }
  }

  private Map<String, Object> deserialize(String body) throws SQLException {
    try {
      return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new SQLException("Receipt decoding failed", e);
    }
  }

  private void metric(String name) {
    metric(name, "NONE");
  }

  private void metric(String name, String code) {
    try {
      Metrics.counter(name, "code", code).increment();
    } catch (RuntimeException ignored) {
      /* Telemetry must not change a committed outcome. */
    }
  }

  private record Account(
      String id,
      String owner,
      String status,
      String currency,
      BigDecimal balance,
      BigDecimal dailyLimit,
      boolean receivingAllowed,
      boolean publicReceive,
      String customerStatus) {}
}
