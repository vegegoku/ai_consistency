package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.Sql;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class PaymentService {
  @Inject DataSource dataSource;
  @Inject CommandValidator validator;
  @Inject MeterRegistry metrics;

  public record Outcome(
      String paymentId,
      String status,
      String fromAccount,
      String toAccount,
      String amount,
      String currency,
      Instant processedAt,
      String rejectionCode,
      int http,
      String requestedBy,
      String fingerprint,
      String correlationId,
      boolean replay) {}

  private record Account(
      String id,
      String customer,
      BigDecimal balance,
      String status,
      String currency,
      boolean receiving,
      BigDecimal limit,
      boolean customerActive) {}

  public Outcome transfer(PaymentCommand cmd, String principal, String correlation) {
    long start = System.nanoTime();
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          return execute(cmd, principal, correlation);
        } catch (SQLException e) {
          String state = e.getSQLState();
          if (state != null
              && (state.equals("40P01") || state.equals("40001") || state.equals("55P03"))
              && attempt < 2) {
            metrics.counter("payments.retries").increment();
            if (state.equals("55P03")) metrics.counter("payments.lock.timeouts").increment();
            try {
              Thread.sleep(25L * (attempt + 1));
            } catch (InterruptedException x) {
              Thread.currentThread().interrupt();
              throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
            }
            continue;
          }
          metrics.counter("payments.failures").increment();
          boolean transientFailure =
              e instanceof SQLTransientException
                  || e instanceof SQLRecoverableException
                  || state == null
                  || state.startsWith("08")
                  || state.startsWith("40")
                  || state.startsWith("53")
                  || state.startsWith("57")
                  || state.equals("55P03");
          throw new PaymentException(
              transientFailure ? 503 : 500,
              transientFailure ? "TEMPORARILY_UNAVAILABLE" : "INTERNAL_ERROR");
        }
      }
    } finally {
      try {
        metrics
            .timer("payments.processing")
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Outcome execute(PaymentCommand cmd, String principal, String correlation)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        Sql.update(c, "SET LOCAL lock_timeout = '1500ms'");
        Sql.update(c, "SET LOCAL statement_timeout = '5s'");
        int inserted =
            Sql.update(
                c,
                "INSERT INTO"
                    + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,requested_by,correlation_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?::uuid) ON CONFLICT(payment_id) DO NOTHING",
                cmd.paymentId(),
                cmd.fromAccount(),
                cmd.toAccount(),
                cmd.amount(),
                cmd.date(),
                cmd.purpose(),
                cmd.notes(),
                cmd.fingerprint(),
                principal,
                correlation);
        Outcome old = load(c, cmd.paymentId());
        if (inserted == 0) {
          if (!old.requestedBy().equals(principal)) throw new PaymentException(403, "FORBIDDEN");
          if (!old.fingerprint().equals(cmd.fingerprint()))
            throw new PaymentException(409, "IDEMPOTENCY_CONFLICT");
          c.commit();
          try {
            metrics.counter("payments.replays").increment();
          } catch (RuntimeException ignored) {
          }
          return new Outcome(
              old.paymentId(),
              old.status(),
              old.fromAccount(),
              old.toAccount(),
              old.amount(),
              old.currency(),
              old.processedAt(),
              old.rejectionCode(),
              old.http(),
              old.requestedBy(),
              old.fingerprint(),
              old.correlationId(),
              true);
        }
        validator.validateTime(cmd);
        Map<String, Account> accounts = new HashMap<>();
        // Separate ordered locking statements make lock acquisition order unambiguous even across
        // query plans.
        for (String id : new TreeSet<>(List.of(cmd.fromAccount(), cmd.toAccount()))) {
          try (var p =
                  Sql.statement(
                      c,
                      "SELECT a.*,c.status AS customer_status FROM accounts a JOIN customers c ON"
                          + " c.id=a.customer_id WHERE a.id=? FOR UPDATE OF a",
                      id);
              var r = p.executeQuery()) {
            if (r.next())
              accounts.put(
                  id,
                  new Account(
                      id,
                      r.getString("customer_id"),
                      r.getBigDecimal("available_balance"),
                      r.getString("status"),
                      r.getString("currency"),
                      r.getBoolean("receive_enabled"),
                      r.getBigDecimal("transfer_limit"),
                      r.getString("customer_status").equals("ACTIVE")));
          }
        }
        Account from = accounts.get(cmd.fromAccount()), to = accounts.get(cmd.toAccount());
        String rejection = null;
        int http = 409;
        if (from == null || !authorized(c, from, principal) || to == null) {
          rejection = "FORBIDDEN";
          http = 403;
        } else if (!from.customerActive()
            || !to.customerActive()
            || !to.receiving()
            || restricted(c, to.id(), from.customer())) {
          rejection = "FORBIDDEN";
          http = 403;
        } else if (!from.status().equals("ACTIVE")
            || !to.status().equals("ACTIVE")
            || !from.currency().equals("JOD")
            || !to.currency().equals("JOD")) rejection = "ACCOUNT_NOT_ACTIVE";
        else if (cmd.amount().compareTo(from.limit()) > 0) rejection = "PAYMENT_LIMIT_EXCEEDED";
        else if (from.balance().compareTo(cmd.amount()) < 0) rejection = "INSUFFICIENT_FUNDS";
        if (rejection == null) {
          BigDecimal debit = from.balance().subtract(cmd.amount()),
              credit = to.balance().add(cmd.amount());
          Sql.update(
              c,
              "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                  + " id=?",
              debit,
              from.id());
          Sql.update(
              c,
              "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE"
                  + " id=?",
              credit,
              to.id());
          Sql.update(
              c,
              "INSERT INTO"
                  + " ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
                  + " VALUES(?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
              cmd.paymentId(),
              from.id(),
              cmd.amount().negate(),
              debit,
              cmd.paymentId(),
              to.id(),
              cmd.amount(),
              credit);
        }
        Sql.update(
            c,
            "UPDATE payments SET status=?,rejection_code=?,rejection_http=?,processed_at=now()"
                + " WHERE payment_id=?",
            rejection == null ? "COMPLETED" : "REJECTED",
            rejection,
            rejection == null ? 201 : http,
            cmd.paymentId());
        Outcome result = load(c, cmd.paymentId());
        c.commit();
        // A telemetry outage must not change a committed outcome.
        try {
          metrics
              .counter(
                  "payments.outcomes",
                  "status",
                  result.status(),
                  "code",
                  rejection == null ? "NONE" : rejection)
              .increment();
        } catch (RuntimeException ignored) {
        }
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
        }
        throw e;
      }
    }
  }

  private boolean authorized(Connection c, Account account, String principal) throws SQLException {
    if (account.customer().equals(principal)) return true;
    try (var p =
            Sql.statement(
                c,
                "SELECT 1 FROM debit_grants WHERE account_id=? AND principal=?",
                account.id(),
                principal);
        var r = p.executeQuery()) {
      return r.next();
    }
  }

  private boolean restricted(Connection c, String id, String customer) throws SQLException {
    try (var p =
            Sql.statement(
                c,
                "SELECT EXISTS(SELECT 1 FROM recipient_restrictions WHERE account_id=?) AND NOT"
                    + " EXISTS(SELECT 1 FROM recipient_restrictions WHERE account_id=? AND"
                    + " allowed_customer_id=?)",
                id,
                id,
                customer);
        var r = p.executeQuery()) {
      r.next();
      return r.getBoolean(1);
    }
  }

  private Outcome load(Connection c, UUID id) throws SQLException {
    try (var p = Sql.statement(c, "SELECT * FROM payments WHERE payment_id=?", id);
        var r = p.executeQuery()) {
      if (!r.next()) throw new PaymentException(404, "PAYMENT_NOT_FOUND");
      Timestamp processed = r.getTimestamp("processed_at");
      return new Outcome(
          id.toString(),
          r.getString("status"),
          r.getString("from_account_id"),
          r.getString("to_account_id"),
          r.getBigDecimal("amount").setScale(3).toPlainString(),
          "JOD",
          processed == null ? null : processed.toInstant(),
          r.getString("rejection_code"),
          r.getInt("rejection_http"),
          r.getString("requested_by"),
          r.getString("payload_fingerprint"),
          r.getString("correlation_id"),
          false);
    }
  }

  public Outcome find(UUID id, String principal) {
    try (var c = dataSource.getConnection()) {
      var result = load(c, id);
      if (!result.requestedBy().equals(principal))
        throw new PaymentException(404, "PAYMENT_NOT_FOUND");
      return result;
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, String> balance(String id, String principal) {
    try (var c = dataSource.getConnection();
        var p =
            Sql.statement(c, "SELECT available_balance,customer_id FROM accounts WHERE id=?", id);
        var r = p.executeQuery()) {
      if (!r.next() || !r.getString("customer_id").equals(principal))
        throw new PaymentException(404, "ACCOUNT_NOT_FOUND");
      return Map.of(
          "accountId",
          id,
          "balance",
          r.getBigDecimal(1).setScale(3).toPlainString(),
          "currency",
          "JOD");
    } catch (SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
