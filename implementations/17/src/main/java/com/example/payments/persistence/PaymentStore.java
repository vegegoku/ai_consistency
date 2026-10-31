package com.example.payments.persistence;

import com.example.payments.domain.PaymentCommand;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Parameterized PostgreSQL persistence operations; the application owns transaction boundaries. */
@Repository
public class PaymentStore {
  private final JdbcTemplate db;

  public PaymentStore(JdbcTemplate db) {
    this.db = db;
  }

  public void boundedTransfer() {
    db.execute("SET LOCAL lock_timeout='1500ms'");
    db.execute("SET LOCAL statement_timeout='5s'");
  }

  public boolean register(PaymentCommand c, String principal, String correlation) {
    return db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES (?::uuid,?,?,?,'JOD',?,?,?,?,'PENDING',?,?::uuid) ON CONFLICT DO"
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

  public Map<String, Object> lockPayment(String id) {
    return db.queryForMap("SELECT * FROM payments WHERE payment_id=?::uuid FOR UPDATE", id);
  }

  public Map<String, Object> payment(String id) {
    return db.queryForMap("SELECT * FROM payments WHERE payment_id=?::uuid", id);
  }

  public List<Map<String, Object>> lockAccounts(String from, String to) {
    return db.queryForList(
        "SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE", from, to);
  }

  public boolean hasDebitGrant(String account, String principal) {
    return db.queryForObject(
            "SELECT count(*) FROM account_debit_grants WHERE account_id=? AND principal=?",
            Integer.class,
            account,
            principal)
        > 0;
  }

  public boolean activeCustomer(String id) {
    return db.queryForObject(
            "SELECT count(*) FROM customers WHERE id=? AND status='ACTIVE'", Integer.class, id)
        == 1;
  }

  public BigDecimal spentToday(String account) {
    return db.queryForObject(
        "SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND"
            + " status='COMPLETED' AND processed_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT"
            + " TIME ZONE 'UTC'",
        BigDecimal.class,
        account);
  }

  public void balance(String account, BigDecimal value) {
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        value,
        account);
  }

  public void appendLedger(PaymentCommand c, BigDecimal debit, BigDecimal credit) {
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES (?::uuid,?,'DEBIT',?,?),(?::uuid,?,'CREDIT',?,?)",
        c.paymentId(),
        c.fromAccount(),
        c.amount().negate(),
        debit,
        c.paymentId(),
        c.toAccount(),
        c.amount(),
        credit);
  }

  public void complete(String id) {
    db.update(
        "UPDATE payments SET status='COMPLETED',processed_at=clock_timestamp() WHERE"
            + " payment_id=?::uuid",
        id);
  }

  public void reject(String id, String code) {
    db.update(
        "UPDATE payments SET status='REJECTED',rejection_code=?,processed_at=clock_timestamp()"
            + " WHERE payment_id=?::uuid",
        code,
        id);
  }

  public List<Map<String, Object>> visiblePayment(UUID id, String principal) {
    return db.queryForList(
        "SELECT p.* FROM payments p WHERE payment_id=? AND (requested_by=? OR EXISTS(SELECT 1 FROM"
            + " accounts a WHERE a.id IN(p.from_account_id,p.to_account_id) AND a.customer_id=?))",
        id,
        principal,
        principal);
  }

  public List<Map<String, Object>> visibleBalance(String account, String principal) {
    return db.queryForList(
        "SELECT available_balance FROM accounts WHERE id=? AND (customer_id=? OR EXISTS(SELECT 1"
            + " FROM account_debit_grants g WHERE g.account_id=accounts.id AND g.principal=?))",
        account,
        principal,
        principal);
  }

  public void reconciliationSnapshot() {
    db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
  }

  public List<Map<String, Object>> unbalancedPayments() {
    return db.queryForList(
        "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l USING(payment_id) WHERE"
            + " p.status='COMPLETED' GROUP BY p.payment_id HAVING count(l.id)<>2 OR"
            + " coalesce(sum(l.signed_amount),0)<>0");
  }

  public List<Map<String, Object>> unbalancedAccounts() {
    return db.queryForList(
        "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP BY a.id"
            + " HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)");
  }
}
