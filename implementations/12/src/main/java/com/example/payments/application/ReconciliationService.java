package com.example.payments.application;

import com.example.payments.domain.ApiException;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class ReconciliationService {
  @Inject AgroalDataSource dataSource;

  public record Report(
      boolean consistent, List<String> paymentIds, List<String> accountIds, String checkedAt) {}

  public Report reconcile() {
    try (var c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (var mode = c.createStatement()) {
        mode.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
      }
      var payments = new ArrayList<String>();
      var accounts = new ArrayList<String>();
      try (var s = c.createStatement();
          var rs =
              s.executeQuery(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " l.payment_id=p.payment_id GROUP BY p.payment_id HAVING"
                      + " (p.status='COMPLETED' AND (count(l.id)<>2 OR"
                      + " coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE"
                      + " (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                      + " l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND"
                      + " l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR"
                      + " (p.status<>'COMPLETED' AND count(l.id)<>0) OR p.status='PROCESSING'")) {
        while (rs.next()) payments.add(rs.getString(1));
      }
      try (var s = c.createStatement();
          var rs =
              s.executeQuery(
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (rs.next()) accounts.add(rs.getString(1));
      }
      c.commit();
      return new Report(
          payments.isEmpty() && accounts.isEmpty(),
          List.copyOf(payments),
          List.copyOf(accounts),
          java.time.Instant.now().toString());
    } catch (SQLException e) {
      throw new ApiException(
          503, "TEMPORARILY_UNAVAILABLE", "Reconciliation is temporarily unavailable.");
    }
  }
}
