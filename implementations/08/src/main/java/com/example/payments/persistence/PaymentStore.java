package com.example.payments.persistence;

import com.example.payments.application.Outcome;
import com.example.payments.domain.PaymentCommand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class PaymentStore {
  private final DataSource data;

  @Inject
  public PaymentStore(DataSource data) {
    this.data = data;
  }

  public Connection connection() throws SQLException {
    return data.getConnection();
  }

  public record Account(
      String id,
      String owner,
      String status,
      BigDecimal balance,
      boolean receive,
      BigDecimal limit,
      String currency) {}

  public record Payment(String fingerprint, String owner, Outcome outcome) {}

  public Connection transaction() throws SQLException {
    var c = data.getConnection();
    c.setAutoCommit(false);
    try (var s = c.createStatement()) {
      s.execute("SET LOCAL lock_timeout='2000ms'");
      s.execute("SET LOCAL statement_timeout='5000ms'");
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  public boolean register(Connection c, PaymentCommand p, String principal, String correlation)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES(?,?,?,?,?,?,?,?, 'PROCESSING',?,?) ON CONFLICT(payment_id) DO"
                + " NOTHING")) {
      s.setObject(1, p.paymentId());
      s.setString(2, p.fromAccount());
      s.setString(3, p.toAccount());
      s.setBigDecimal(4, p.amount());
      s.setTimestamp(5, Timestamp.from(p.date()));
      s.setString(6, p.purpose());
      s.setString(7, p.notes());
      s.setString(8, p.fingerprint());
      s.setString(9, principal);
      s.setObject(10, safeUuid(correlation));
      return s.executeUpdate() == 1;
    }
  }

  public static UUID safeUuid(String v) {
    try {
      return UUID.fromString(v);
    } catch (Exception e) {
      return UUID.randomUUID();
    }
  }

  public Payment payment(Connection c, UUID id) throws SQLException {
    try (var s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
      s.setObject(1, id);
      try (var r = s.executeQuery()) {
        if (!r.next()) return null;
        String status = r.getString("status");
        var body = new LinkedHashMap<String, Object>();
        body.put("paymentId", id.toString());
        body.put("status", status);
        int http = 200;
        if (status.equals("COMPLETED")) {
          body.put("fromAccount", r.getString("from_account_id"));
          body.put("toAccount", r.getString("to_account_id"));
          body.put("amount", r.getBigDecimal("amount").toPlainString());
          body.put("currency", r.getString("currency"));
          body.put("processedAt", r.getTimestamp("processed_at").toInstant().toString());
        } else if (status.equals("REJECTED")) {
          String code = r.getString("rejection_code");
          http = code.equals("FORBIDDEN") ? 403 : 409;
          body.put("code", code);
          body.put("message", "The payment could not be completed.");
          body.put("issues", List.of());
          body.put("correlationId", r.getString("correlation_id"));
          body.put("timestamp", r.getTimestamp("processed_at").toInstant().toString());
        } else throw new SQLException("Uncommitted payment state", "40001");
        return new Payment(
            r.getString("payload_fingerprint"),
            r.getString("requested_by"),
            new Outcome(http, body, true));
      }
    }
  }

  public Map<String, Account> lockAccounts(Connection c, String first, String second)
      throws SQLException {
    var out = new HashMap<String, Account>();
    // Separate sorted statements ensure actual lock acquisition order, independent of query
    // planner.
    for (String id : List.of(first, second).stream().sorted().toList())
      try (var s = c.prepareStatement("SELECT * FROM accounts WHERE id=? FOR UPDATE")) {
        s.setString(1, id);
        try (var r = s.executeQuery()) {
          if (r.next())
            out.put(
                id,
                new Account(
                    id,
                    r.getString("customer_id"),
                    r.getString("status"),
                    r.getBigDecimal("available_balance"),
                    r.getBoolean("can_receive"),
                    r.getBigDecimal("transfer_limit"),
                    r.getString("currency")));
        }
      }
    return out;
  }

  public boolean authorized(Connection c, String account, String principal) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT 1 FROM accounts a WHERE a.id=? AND (a.customer_id=? OR EXISTS(SELECT 1 FROM"
                + " account_debit_authorizations d WHERE d.account_id=a.id AND d.principal=?))")) {
      s.setString(1, account);
      s.setString(2, principal);
      s.setString(3, principal);
      try (var r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  public void balance(Connection c, String account, BigDecimal amount) throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                + " id=?")) {
      s.setBigDecimal(1, amount);
      s.setString(2, account);
      if (s.executeUpdate() != 1) throw new SQLException("Account update failed");
    }
  }

  public void ledger(
      Connection c,
      PaymentCommand p,
      String account,
      String type,
      BigDecimal signed,
      BigDecimal balance)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                + " VALUES(?,?,?,?,?)")) {
      s.setObject(1, p.paymentId());
      s.setString(2, account);
      s.setString(3, type);
      s.setBigDecimal(4, signed);
      s.setBigDecimal(5, balance);
      s.executeUpdate();
    }
  }

  public void finish(Connection c, UUID id, String error) throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE payments SET status=?,rejection_code=?,processed_at=clock_timestamp() WHERE"
                + " payment_id=?")) {
      s.setString(1, error == null ? "COMPLETED" : "REJECTED");
      s.setString(2, error);
      s.setObject(3, id);
      s.executeUpdate();
    }
  }

  public List<Map<String, Object>> reconciliation() throws SQLException {
    try (var c = data.getConnection()) {
      c.setAutoCommit(false);
      try (var timeout = c.createStatement()) {
        timeout.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        timeout.execute("SET LOCAL statement_timeout='10000ms'");
      }
      var failures = new ArrayList<Map<String, Object>>();
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " l.payment_id=p.payment_id WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0"
                      + " OR count(*) FILTER(WHERE (l.entry_type='DEBIT' AND"
                      + " l.account_id=p.from_account_id AND l.signed_amount=-p.amount) OR"
                      + " (l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND"
                      + " l.signed_amount=p.amount))<>2")) {
        while (r.next())
          failures.add(Map.of("type", "PAYMENT_LEDGER_MISMATCH", "paymentId", r.getString(1)));
      }
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (r.next())
          failures.add(Map.of("type", "ACCOUNT_BALANCE_MISMATCH", "accountId", r.getString(1)));
      }
      c.commit();
      return failures;
    }
  }
}
