package com.example.payments.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Transaction-bound account and ledger persistence. Caller supplies its enlisted connection. */
@ApplicationScoped
public class AccountStore {
  public record Account(
      String id,
      String customer,
      String currency,
      BigDecimal balance,
      String status,
      boolean incoming,
      BigDecimal limit) {}

  public Map<String, Account> lock(Connection c, String source, String destination)
      throws SQLException {
    Map<String, Account> accounts = new HashMap<>();
    try (var s =
        c.prepareStatement("SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
      s.setString(1, source);
      s.setString(2, destination);
      try (var r = s.executeQuery()) {
        while (r.next())
          accounts.put(
              r.getString("id"),
              new Account(
                  r.getString("id"),
                  r.getString("customer_id"),
                  r.getString("currency"),
                  r.getBigDecimal("available_balance"),
                  r.getString("status"),
                  r.getBoolean("allow_incoming"),
                  r.getBigDecimal("transfer_limit")));
      }
    }
    return accounts;
  }

  public boolean mayDebit(Connection c, Account from, String principal) throws SQLException {
    boolean permitted = from != null && from.customer().equals(principal);
    if (!permitted && from != null)
      try (var s =
          c.prepareStatement(
              "SELECT 1 FROM account_debit_grants WHERE account_id=? AND principal=?")) {
        s.setString(1, from.id());
        s.setString(2, principal);
        try (var r = s.executeQuery()) {
          permitted = r.next();
        }
      }
    return permitted;
  }

  public void updateBalance(Connection c, String id, BigDecimal balance) throws SQLException {
    try (var s =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=clock_timestamp()"
                + " WHERE id=?")) {
      s.setBigDecimal(1, balance);
      s.setString(2, id);
      s.executeUpdate();
    }
  }

  public void ledger(
      Connection c, UUID payment, String account, String type, BigDecimal amount, BigDecimal after)
      throws SQLException {
    try (var s =
        c.prepareStatement(
            "INSERT INTO"
                + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                + " VALUES (?,?,?,?,?)")) {
      s.setObject(1, payment);
      s.setString(2, account);
      s.setString(3, type);
      s.setBigDecimal(4, amount);
      s.setBigDecimal(5, after);
      s.executeUpdate();
    }
  }
}
