package com.example.payments.application;

import com.example.payments.domain.PaymentException;
import com.example.payments.persistence.Sql;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class ReconciliationService {
  @Inject DataSource db;

  public Map<String, Object> report() {
    try (var c = db.getConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      var badPayments = new ArrayList<String>();
      var badAccounts = new ArrayList<String>();
      try (var p =
              Sql.statement(
                  c,
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries e ON"
                      + " e.payment_id=p.payment_id WHERE p.status='COMPLETED' GROUP BY"
                      + " p.payment_id HAVING count(e.id)<>2 OR"
                      + " coalesce(sum(e.signed_amount),0)<>0");
          var r = p.executeQuery()) {
        while (r.next()) badPayments.add(r.getString(1));
      }
      try (var p =
              Sql.statement(
                  c,
                  "SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id"
                      + " GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(e.signed_amount),0)");
          var r = p.executeQuery()) {
        while (r.next()) badAccounts.add(r.getString(1));
      }
      c.commit();
      return Map.of(
          "balanced",
          badPayments.isEmpty() && badAccounts.isEmpty(),
          "paymentDiscrepancies",
          badPayments,
          "accountDiscrepancies",
          badAccounts);
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
