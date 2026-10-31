package com.example.payments.persistence;

import com.example.payments.domain.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** JDBC operations shared by transactional application use cases. */
public final class FinancialRepository {
  private final DataSource ds;

  public FinancialRepository(DataSource ds) {
    this.ds = ds;
  }

  public static boolean canDebit(Connection c, String id, Caller u) throws SQLException {
    if (u.admin()) return true;
    try (var s =
        c.prepareStatement(
            "SELECT 1 FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE a.id=? AND"
                + " c.status='ACTIVE' AND(a.customer_id=? OR EXISTS(SELECT 1 FROM"
                + " account_permissions p WHERE p.account_id=a.id AND p.principal=? AND"
                + " p.can_debit))")) {
      s.setString(1, id);
      s.setString(2, u.subject());
      s.setString(3, u.subject());
      try (var r = s.executeQuery()) {
        return r.next();
      }
    }
  }

  public static boolean canReceive(Connection c, String id, Caller user) throws SQLException {
    try (var s =
        c.prepareStatement(
            "SELECT 1 FROM account_permissions WHERE account_id=? AND principal=? AND NOT"
                + " can_receive")) {
      s.setString(1, id);
      s.setString(2, user.subject());
      try (var r = s.executeQuery()) {
        return !r.next();
      }
    }
  }

  public static void update(Connection c, String id, BigDecimal balance) throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                + " id=?")) {
      s.setBigDecimal(1, balance);
      s.setString(2, id);
      s.executeUpdate();
    }
  }

  public static void ledger(
      Connection c,
      UUID payment,
      String account,
      String type,
      BigDecimal signed,
      BigDecimal balance)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)VALUES(?,?,?,?,?)")) {
      s.setObject(1, payment);
      s.setString(2, account);
      s.setString(3, type);
      s.setBigDecimal(4, signed);
      s.setBigDecimal(5, balance);
      s.executeUpdate();
    }
  }

  public static Outcome outcome(ResultSet r, boolean replay) throws SQLException {
    return new Outcome(
        r.getObject("payment_id", UUID.class),
        r.getString("status"),
        r.getString("from_account_id"),
        r.getString("to_account_id"),
        r.getBigDecimal("amount").setScale(3).toPlainString(),
        r.getString("currency"),
        r.getTimestamp("processed_at").toInstant(),
        r.getString("rejection_code"),
        replay);
  }

  public Outcome get(UUID id, Caller user) {
    try (var c = ds.getConnection();
        var s =
            c.prepareStatement(
                "SELECT * FROM payments WHERE payment_id=? AND(requested_by=? OR ?)")) {
      s.setObject(1, id);
      s.setString(2, user.subject());
      s.setBoolean(3, user.admin());
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new ApiException(404, "NOT_FOUND");
        return outcome(r, true);
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, String> balance(String id, Caller user) {
    try (var c = ds.getConnection()) {
      if (!canDebit(c, id, user)) throw new ApiException(404, "NOT_FOUND");
      try (var s = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
        s.setString(1, id);
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
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public List<String> reconcile() {
    var issues = new ArrayList<String>();
    try (var c = ds.getConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " l.payment_id=p.payment_id WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0"
                      + " OR count(*) FILTER(WHERE l.entry_type='DEBIT' AND"
                      + " l.account_id=p.from_account_id AND l.signed_amount=-p.amount)<>1 OR"
                      + " count(*) FILTER(WHERE l.entry_type='CREDIT' AND"
                      + " l.account_id=p.to_account_id AND l.signed_amount=p.amount)<>1")) {
        while (r.next()) issues.add("PAYMENT:" + r.getString(1));
      }
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (r.next()) issues.add("ACCOUNT:" + r.getString(1));
      }
      c.commit();
      return issues;
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public boolean rateAllowed(String principal, int limit) {
    try (var c = ds.getConnection();
        var s =
            c.prepareStatement(
                "INSERT INTO"
                    + " rate_buckets(principal,window_start,hits)VALUES(?,date_trunc('minute',now()),1)"
                    + " ON CONFLICT(principal) DO UPDATE SET hits=CASE WHEN"
                    + " rate_buckets.window_start=EXCLUDED.window_start THEN rate_buckets.hits+1"
                    + " ELSE 1 END,window_start=EXCLUDED.window_start RETURNING hits")) {
      s.setString(1, principal);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getInt(1) <= limit;
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
