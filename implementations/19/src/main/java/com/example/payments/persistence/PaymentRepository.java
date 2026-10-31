package com.example.payments.persistence;

import com.example.payments.domain.Command;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized SQL only; callers must establish a transaction for mutation and row locks. */
@Repository
public class PaymentRepository {
  private final JdbcTemplate db;

  public PaymentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public void boundedLocks() {
    db.execute("SET LOCAL lock_timeout='2s'");
    db.execute("SET LOCAL statement_timeout='5s'");
  }

  public boolean register(Command c, String principal, UUID correlation) {
    return db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES (?,?,?,?,'JOD',?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO"
                + " NOTHING",
            c.paymentId(),
            c.fromAccount(),
            c.toAccount(),
            c.amount(),
            Timestamp.from(c.date()),
            c.purpose(),
            c.notes(),
            c.fingerprint(),
            principal,
            correlation)
        == 1;
  }

  public Map<String, Object> lockPayment(UUID id) {
    return db.queryForMap("SELECT * FROM payments WHERE payment_id=? FOR UPDATE", id);
  }

  public Map<String, Object> payment(UUID id) {
    return db.queryForMap("SELECT * FROM payments WHERE payment_id=?", id);
  }

  public List<Map<String, Object>> lockAccounts(String from, String to) {
    return db.queryForList(
        "SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE", from, to);
  }

  public void reject(UUID id, String code, int http) {
    db.update(
        "UPDATE payments SET"
            + " status='REJECTED',rejection_code=?,rejection_http=?,processed_at=clock_timestamp()"
            + " WHERE payment_id=?",
        code,
        http,
        id);
  }

  public void balance(String account, BigDecimal value) {
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=clock_timestamp()"
            + " WHERE id=?",
        value,
        account);
  }

  public void ledger(Command c, BigDecimal source, BigDecimal target) {
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES (?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
        c.paymentId(),
        c.fromAccount(),
        c.amount().negate(),
        source,
        c.paymentId(),
        c.toAccount(),
        c.amount(),
        target);
  }

  public void complete(UUID id) {
    db.update(
        "UPDATE payments SET status='COMPLETED',processed_at=clock_timestamp() WHERE payment_id=?",
        id);
  }

  public boolean granted(String account, String principal, boolean debit) {
    String sql =
        debit
            ? "SELECT EXISTS(SELECT 1 FROM account_permissions WHERE account_id=? AND principal=?"
                + " AND can_debit)"
            : "SELECT EXISTS(SELECT 1 FROM account_permissions WHERE account_id=? AND principal=?"
                + " AND can_view)";
    return Boolean.TRUE.equals(db.queryForObject(sql, Boolean.class, account, principal));
  }

  public List<Map<String, Object>> findPayment(UUID id) {
    return db.queryForList("SELECT * FROM payments WHERE payment_id=?", id);
  }

  public List<Map<String, Object>> findAccount(String id) {
    return db.queryForList("SELECT * FROM accounts WHERE id=?", id);
  }
}
