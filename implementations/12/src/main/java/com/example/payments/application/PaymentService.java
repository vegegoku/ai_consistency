package com.example.payments.application;

import static com.example.payments.persistence.Sql.*;

import com.example.payments.domain.*;
import com.example.payments.observability.TransferTelemetry;
import com.example.payments.security.*;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class PaymentService {
  @Inject AgroalDataSource dataSource;
  @Inject Limits limits;
  @Inject TransferTelemetry telemetry;

  private record Account(
      String id,
      BigDecimal balance,
      String status,
      String currency,
      boolean receives,
      BigDecimal maximum) {}

  public PaymentResult transfer(PaymentCommand cmd, Caller caller, UUID correlation) {
    caller.require("payments:write");
    long started = System.nanoTime();
    for (int attempt = 0; ; attempt++) {
      try (var c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          configure(c);
          boolean inserted =
              update(
                      c,
                      "INSERT INTO"
                          + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                          + " VALUES(?,?,?,?,'JOD',?,?,?,?,'PROCESSING',?,?) ON"
                          + " CONFLICT(payment_id) DO NOTHING",
                      cmd.paymentId(),
                      cmd.fromAccount(),
                      cmd.toAccount(),
                      cmd.amount(),
                      cmd.date(),
                      cmd.purpose(),
                      cmd.notes(),
                      cmd.fingerprint(),
                      caller.subject(),
                      correlation)
                  == 1;
          if (!inserted) {
            var original = read(c, cmd.paymentId());
            if (!original.principal.equals(caller.subject())
                && !AccountAccess.debit(c, original.receipt.fromAccount(), caller.subject()))
              throw new ApiException(403, "FORBIDDEN", "The operation is not permitted.");
            if (!original.fingerprint.equals(cmd.fingerprint()))
              throw new ApiException(
                  409,
                  "IDEMPOTENCY_CONFLICT",
                  "The payment ID is already bound to another instruction.",
                  cmd.paymentId(),
                  List.of(),
                  false);
            c.commit();
            var result = original.result(true);
            telemetry.committed(
                cmd.paymentId(),
                result.receipt().status(),
                result.rejectionCode(),
                true,
                System.nanoTime() - started);
            return result;
          }
          // A single ordered SELECT locks both rows before any business decision or balance update.
          Map<String, Account> accounts = new HashMap<>();
          try (var s =
                  statement(
                      c,
                      "SELECT id,available_balance,status,currency,receive_enabled,max_transfer"
                          + " FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                      cmd.fromAccount(),
                      cmd.toAccount());
              var rs = s.executeQuery()) {
            while (rs.next())
              accounts.put(
                  rs.getString(1),
                  new Account(
                      rs.getString(1),
                      rs.getBigDecimal(2),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getBoolean(5),
                      rs.getBigDecimal(6)));
          }
          Account from = accounts.get(cmd.fromAccount()), to = accounts.get(cmd.toAccount());
          String rejection = null;
          int http = 409;
          if (from == null || !AccountAccess.debit(c, cmd.fromAccount(), caller.subject())) {
            rejection = "FORBIDDEN";
            http = 403;
          } else if (to == null) {
            rejection = "FORBIDDEN";
            http = 403;
          } else if (!from.status.equals("ACTIVE")
              || !to.status.equals("ACTIVE")
              || !to.receives
              || !"JOD".equals(from.currency)
              || !"JOD".equals(to.currency)) {
            rejection = "ACCOUNT_NOT_ACTIVE";
          } else if (cmd.amount().compareTo(from.maximum) > 0
              || cmd.amount().compareTo(to.maximum) > 0
              || to.balance.add(cmd.amount()).precision() > 19) {
            rejection = "PAYMENT_LIMIT_EXCEEDED";
          } else if (from.balance.compareTo(cmd.amount()) < 0) {
            rejection = "INSUFFICIENT_FUNDS";
          }
          if (rejection != null) {
            update(
                c,
                "UPDATE payments SET"
                    + " status='REJECTED',rejection_code=?,rejection_http=?,processed_at=clock_timestamp()"
                    + " WHERE payment_id=?",
                rejection,
                http,
                cmd.paymentId());
          } else {
            BigDecimal sourceAfter = from.balance.subtract(cmd.amount()),
                destAfter = to.balance.add(cmd.amount());
            update(
                c,
                "UPDATE accounts SET"
                    + " available_balance=?,version=version+1,updated_at=clock_timestamp() WHERE"
                    + " id=?",
                sourceAfter,
                from.id);
            telemetry.afterDebit();
            update(
                c,
                "UPDATE accounts SET"
                    + " available_balance=?,version=version+1,updated_at=clock_timestamp() WHERE"
                    + " id=?",
                destAfter,
                to.id);
            update(
                c,
                "INSERT INTO"
                    + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                    + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
                cmd.paymentId(),
                from.id,
                cmd.amount().negate(),
                sourceAfter,
                cmd.paymentId(),
                to.id,
                cmd.amount(),
                destAfter);
            update(
                c,
                "UPDATE payments SET status='COMPLETED',processed_at=clock_timestamp() WHERE"
                    + " payment_id=?",
                cmd.paymentId());
          }
          var result = read(c, cmd.paymentId()).result(false);
          c.commit();
          telemetry.committed(
              cmd.paymentId(),
              result.receipt().status(),
              result.rejectionCode(),
              false,
              System.nanoTime() - started);
          return result;
        } catch (Exception e) {
          try {
            c.rollback();
          } catch (SQLException rollback) {
            e.addSuppressed(rollback);
          }
          throw e;
        }
      } catch (SQLException e) {
        String state = e.getSQLState();
        org.jboss.logging.Logger.getLogger(PaymentService.class)
            .warnv("Database operation failed SQLState={0}", state);
        boolean retryable =
            Set.of("40001", "40P01", "55P03", "57014").contains(state == null ? "" : state);
        if (retryable && attempt < limits.retries) {
          telemetry.retry(state);
          continue;
        }
        throw new ApiException(
            503,
            "TEMPORARILY_UNAVAILABLE",
            "Processing is temporarily unavailable. Query status or retry with the same payment"
                + " ID.",
            cmd.paymentId(),
            List.of(),
            false);
      }
    }
  }

  private void configure(Connection c) throws SQLException {
    string(c, "SELECT set_config('lock_timeout',?,true)", limits.lockMs + "ms");
    string(c, "SELECT set_config('statement_timeout',?,true)", limits.statementMs + "ms");
  }

  private record Stored(
      Receipt receipt, String fingerprint, String principal, String rejection, int http) {
    PaymentResult result(boolean replay) {
      return new PaymentResult(receipt, rejection, http, replay);
    }
  }

  private Stored read(Connection c, UUID id) throws SQLException {
    try (var s =
            statement(
                c,
                "SELECT"
                    + " payment_id,status,from_account_id,to_account_id,amount,currency,processed_at,payload_fingerprint,requested_by,rejection_code,rejection_http"
                    + " FROM payments WHERE payment_id=?",
                id);
        var rs = s.executeQuery()) {
      if (!rs.next()) throw new ApiException(404, "PAYMENT_NOT_FOUND", "Payment not found.");
      return new Stored(
          new Receipt(
              rs.getString(1),
              rs.getString(2),
              rs.getString(3),
              rs.getString(4),
              rs.getBigDecimal(5).setScale(3).toPlainString(),
              rs.getString(6),
              rs.getTimestamp(7).toInstant().toString()),
          rs.getString(8),
          rs.getString(9),
          rs.getString(10),
          rs.getInt(11));
    }
  }

  public PaymentResult find(UUID id, Caller caller) {
    caller.require("payments:read");
    try (var c = dataSource.getConnection()) {
      var payment = read(c, id);
      if (!payment.principal.equals(caller.subject())
          && !AccountAccess.view(c, payment.receipt.fromAccount(), caller.subject())
          && !AccountAccess.view(c, payment.receipt.toAccount(), caller.subject()))
        throw new ApiException(404, "PAYMENT_NOT_FOUND", "Payment not found.");
      return payment.result(true);
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE", "Status is temporarily unavailable.");
    }
  }

  public Map<String, String> balance(String account, Caller caller) {
    caller.require("accounts:read");
    try (var c = dataSource.getConnection()) {
      if (!AccountAccess.view(c, account, caller.subject()))
        throw new ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found.");
      try (var s =
              statement(c, "SELECT available_balance,currency FROM accounts WHERE id=?", account);
          var rs = s.executeQuery()) {
        if (!rs.next()) throw new ApiException(404, "ACCOUNT_NOT_FOUND", "Account not found.");
        return Map.of(
            "accountId",
            account,
            "availableBalance",
            rs.getBigDecimal(1).setScale(3).toPlainString(),
            "currency",
            rs.getString(2));
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE", "Balance is temporarily unavailable.");
    }
  }
}
