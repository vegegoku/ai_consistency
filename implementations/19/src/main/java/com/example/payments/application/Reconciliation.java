package com.example.payments.application;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Reconciliation {
  private final JdbcTemplate db;

  public Reconciliation(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> report() {
    return db.queryForList(
        "SELECT 'PAYMENT_LEDGER' AS issue,p.payment_id::text AS reference FROM payments p LEFT JOIN"
            + " ledger_entries l ON l.payment_id=p.payment_id GROUP BY p.payment_id HAVING"
            + " (p.status='COMPLETED' AND (count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0))"
            + " OR (p.status<>'COMPLETED' AND count(l.id)<>0) UNION ALL SELECT"
            + " 'ACCOUNT_BALANCE',a.id FROM accounts a LEFT JOIN ledger_entries l ON"
            + " l.account_id=a.id GROUP BY a.id HAVING"
            + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)");
  }
}
