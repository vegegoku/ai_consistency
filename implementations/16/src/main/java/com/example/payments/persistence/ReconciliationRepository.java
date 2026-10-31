package com.example.payments.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {
  private final JdbcTemplate db;

  public ReconciliationRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> report() {
    var results = new ArrayList<Map<String, Object>>();
    results.addAll(
        db.queryForList(
            "SELECT 'PAYMENT_LEDGER_MISMATCH' AS issue,p.payment_id::text AS reference FROM"
                + " payments p LEFT JOIN ledger_entries l ON l.payment_id=p.payment_id WHERE"
                + " p.status='COMPLETED' GROUP BY p.payment_id HAVING count(l.id)<>2 OR"
                + " coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE"
                + " (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                + " l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND"
                + " l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2"));
    results.addAll(
        db.queryForList(
            "SELECT 'ACCOUNT_BALANCE_MISMATCH' AS issue,a.id AS reference FROM accounts a LEFT JOIN"
                + " ledger_entries l ON l.account_id=a.id GROUP BY a.id HAVING"
                + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)"));
    return results;
  }
}
