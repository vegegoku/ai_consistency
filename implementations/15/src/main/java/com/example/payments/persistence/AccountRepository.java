package com.example.payments.persistence;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
  private final JdbcTemplate db;

  public AccountRepository(JdbcTemplate db) {
    this.db = db;
  }

  public record Account(
      String id,
      String subject,
      String customerStatus,
      String currency,
      BigDecimal balance,
      String status,
      boolean receive,
      boolean transfer,
      boolean unrestricted,
      BigDecimal transferLimit,
      BigDecimal dailyLimit) {}

  public List<Account> lock(String from, String to) {
    return db.query(
        "SELECT a.*,c.subject,c.status customer_status FROM accounts a JOIN customers c ON"
            + " c.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a,c",
        (r, n) ->
            new Account(
                r.getString("id"),
                r.getString("subject"),
                r.getString("customer_status"),
                r.getString("currency"),
                r.getBigDecimal("available_balance"),
                r.getString("status"),
                r.getBoolean("receive_enabled"),
                r.getBoolean("transfer_enabled"),
                r.getBoolean("unrestricted_destinations"),
                r.getBigDecimal("transfer_limit"),
                r.getBigDecimal("daily_limit")),
        from,
        to);
  }

  public boolean authorized(String account, String subject) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE"
                + " a.id=? AND (c.subject=? OR EXISTS(SELECT 1 FROM account_debit_authorizations g"
                + " WHERE g.account_id=a.id AND g.subject=?)))",
            Boolean.class,
            account,
            subject,
            subject));
  }

  public boolean allowedDestination(String from, String to) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM account_destinations WHERE from_account_id=? AND"
                + " to_account_id=?)",
            Boolean.class,
            from,
            to));
  }

  public void balance(String id, BigDecimal amount) {
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        amount,
        id);
  }
}
