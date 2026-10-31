package com.example.payments.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class Reconciliation {
  private final DataSource ds;

  @Inject
  public Reconciliation(DataSource ds) {
    this.ds = ds;
  }

  public List<Map<String, String>> report() {
    try (var c = ds.getConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      c.setReadOnly(true);
      var issues = new ArrayList<Map<String, String>>();
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " p.payment_id=l.payment_id WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id,p.amount,p.from_account_id,p.to_account_id HAVING"
                      + " count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR count(*)"
                      + " FILTER(WHERE l.entry_type='DEBIT' AND l.signed_amount=-p.amount AND"
                      + " l.account_id=p.from_account_id)<>1 OR count(*) FILTER(WHERE"
                      + " l.entry_type='CREDIT' AND l.signed_amount=p.amount AND"
                      + " l.account_id=p.to_account_id)<>1")) {
        while (r.next()) issues.add(Map.of("type", "UNBALANCED_PAYMENT", "id", r.getString(1)));
      }
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON a.id=l.account_id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (r.next()) issues.add(Map.of("type", "BALANCE_MISMATCH", "id", r.getString(1)));
      }
      c.commit();
      return issues;
    } catch (SQLException e) {
      throw new com.example.payments.domain.ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
