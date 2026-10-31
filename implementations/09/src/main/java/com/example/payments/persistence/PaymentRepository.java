package com.example.payments.persistence;

import com.example.payments.domain.PaymentCommand;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit SQL persistence; callers establish the enclosing transfer transaction. */
@Repository
public class PaymentRepository {
  private final JdbcTemplate db;

  public PaymentRepository(JdbcTemplate db) {
    this.db = db;
  }

  public boolean register(
      PaymentCommand c, String fingerprint, String principal, String correlation) {
    return db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES(?,?,?,?,'JOD',?,?,?,?,'PROCESSING',?,?::uuid) ON CONFLICT(payment_id) DO"
                + " NOTHING",
            c.paymentId(),
            c.fromAccount(),
            c.toAccount(),
            c.amount(),
            Timestamp.from(c.date()),
            c.purpose(),
            c.notes(),
            fingerprint,
            principal,
            correlation)
        == 1;
  }

  public List<Map<String, Object>> lockAccounts(String from, String to) {
    return db.queryForList(
        "SELECT a.*,c.status customer_status FROM accounts a JOIN customers c ON c.id=a.customer_id"
            + " WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a",
        from,
        to);
  }

  public void updateBalance(String account, BigDecimal balance) {
    db.update(
        "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?",
        balance,
        account);
  }

  public void appendLedger(PaymentCommand c, BigDecimal debitBalance, BigDecimal creditBalance) {
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
        c.paymentId(),
        c.fromAccount(),
        c.amount().negate(),
        debitBalance,
        c.paymentId(),
        c.toAccount(),
        c.amount(),
        creditBalance);
  }

  public void finalizePayment(UUID id, String status, String code, String outcome, int http) {
    db.update(
        "UPDATE payments SET"
            + " status=?,rejection_code=?,processed_at=now(),outcome=?::jsonb,http_status=? WHERE"
            + " payment_id=?",
        status,
        code,
        outcome,
        http,
        id);
  }
}
