package com.example.payments.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-side queries enforce visibility in SQL before returning financial data. */
@Repository
public class FinancialQueries {
  private final JdbcTemplate db;

  public FinancialQueries(JdbcTemplate db) {
    this.db = db;
  }

  public Optional<String> payment(UUID id, String principal) {
    var rows =
        db.queryForList(
            """
            SELECT response_json FROM payments p WHERE payment_id=? AND
            (requested_by=? OR EXISTS(SELECT 1 FROM accounts a WHERE
            a.id IN(p.from_account_id,p.to_account_id) AND (a.customer_id=? OR
            EXISTS(SELECT 1 FROM account_permissions ap WHERE ap.account_id=a.id
            AND ap.principal=? AND ap.can_view))))
            """,
            id,
            principal,
            principal,
            principal);
    return rows.isEmpty()
        ? Optional.empty()
        : Optional.of((String) rows.getFirst().get("response_json"));
  }

  public Optional<BigDecimal> balance(String account, String principal) {
    var rows =
        db.queryForList(
            """
            SELECT available_balance FROM accounts a WHERE id=? AND
            (customer_id=? OR EXISTS(SELECT 1 FROM account_permissions ap WHERE
            ap.account_id=a.id AND ap.principal=? AND ap.can_view))
            """,
            account,
            principal,
            principal);
    return rows.isEmpty()
        ? Optional.empty()
        : Optional.of((BigDecimal) rows.getFirst().get("available_balance"));
  }

  /** One statement provides one MVCC snapshot across both financial invariants. */
  public List<Map<String, Object>> reconcile() {
    return db.queryForList(
        """
        SELECT 'LEDGER_PAIR' AS issue,p.payment_id::text AS reference
        FROM payments p LEFT JOIN ledger_entries l ON l.payment_id=p.payment_id
        WHERE p.status='COMPLETED' GROUP BY p.payment_id
        HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0
        UNION ALL
        SELECT 'BALANCE',a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id
        GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)
        """);
  }

  public Map<String, Object> retention(int days) {
    return Map.of(
        "minimumRetentionDays",
        days,
        "cutoff",
        Instant.now().minusSeconds(days * 86400L).toString(),
        "paymentsOlderThanMinimum",
        db.queryForObject(
            "SELECT count(*) FROM payments WHERE created_at<now()-(? * interval '1 day')",
            Long.class,
            days),
        "action",
        "ARCHIVE_REVIEW_ONLY_NO_AUTOMATIC_DELETION");
  }
}
