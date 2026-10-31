package com.example.payments.application;

import static com.example.payments.persistence.FinancialRepository.*;

import com.example.payments.domain.*;
import com.example.payments.persistence.FinancialRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;

@ApplicationScoped
public class PaymentService {
  private final DataSource ds;
  private final FinancialRepository repository;
  private final Consumer<String> hook;

  @Inject
  public PaymentService(DataSource ds) {
    this(ds, s -> {});
  }

  public PaymentService(DataSource ds, Consumer<String> hook) {
    this.ds = ds;
    this.repository = new FinancialRepository(ds);
    this.hook = hook;
  }

  public Outcome transfer(PaymentCommand command, Caller caller, UUID correlation) {
    for (int attempt = 0; attempt < 3; attempt++)
      try {
        return execute(command, caller, correlation);
      } catch (SQLException e) {
        metric(
            "payment_lock_timeouts",
            Set.of("55P03", "57014").contains(Objects.toString(e.getSQLState(), "")));
        if (attempt < 2
            && Set.of("40001", "40P01", "55P03", "57014")
                .contains(Objects.toString(e.getSQLState(), ""))) {
          metric("payment_retries", true);
          try {
            Thread.sleep(25L * (attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
          }
          continue;
        }
        throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
      }
    throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
  }

  private Outcome execute(PaymentCommand p, Caller user, UUID correlation) throws SQLException {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (var s = c.createStatement()) {
          s.execute("SET LOCAL lock_timeout='2s'");
          s.execute("SET LOCAL statement_timeout='5s'");
        }
        if (!canDebit(c, p.fromAccount(), user)) throw new ApiException(403, "FORBIDDEN");
        int inserted;
        try (var s =
            c.prepareStatement(
                "INSERT INTO"
                    + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,rejection_code,processed_at,requested_by,correlation_id)"
                    + " VALUES(?,?,?,?,'JOD',?,?,?,?, 'REJECTED','PROCESSING',now(),?,?) ON"
                    + " CONFLICT(payment_id) DO NOTHING")) {
          s.setObject(1, p.paymentId());
          s.setString(2, p.fromAccount());
          s.setString(3, p.toAccount());
          s.setBigDecimal(4, p.amount());
          s.setTimestamp(5, Timestamp.from(p.date()));
          s.setString(6, p.purpose());
          s.setString(7, p.notes());
          s.setString(8, p.fingerprint());
          s.setString(9, user.subject());
          s.setObject(10, correlation);
          inserted = s.executeUpdate();
        }
        if (inserted == 0) {
          Outcome old;
          try (var s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
            s.setObject(1, p.paymentId());
            try (var r = s.executeQuery()) {
              r.next();
              if (!r.getString("requested_by").equals(user.subject()) && !user.admin())
                throw new ApiException(404, "NOT_FOUND");
              if (!r.getString("payload_fingerprint").equals(p.fingerprint()))
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
              old = outcome(r, true);
            }
          }
          c.commit();
          return old;
        }
        Map<String, Account> accounts = new HashMap<>();
        try (var s =
            c.prepareStatement("SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
          s.setString(1, p.fromAccount());
          s.setString(2, p.toAccount());
          try (var r = s.executeQuery()) {
            while (r.next())
              accounts.put(
                  r.getString("id"),
                  new Account(
                      r.getString("id"),
                      r.getBigDecimal("available_balance"),
                      r.getString("status"),
                      r.getString("currency"),
                      r.getBigDecimal("max_transfer"),
                      r.getBoolean("receive_enabled")));
          }
        }
        Account from = accounts.get(p.fromAccount()), to = accounts.get(p.toAccount());
        String error = null;
        if (from == null || to == null) error = user.admin() ? "ACCOUNT_NOT_FOUND" : "FORBIDDEN";
        else if (!canDebit(c, p.fromAccount(), user)
            || !to.receives
            || !canReceive(c, p.toAccount(), user)) error = "FORBIDDEN";
        else if (!from.status.equals("ACTIVE")
            || !to.status.equals("ACTIVE")
            || !from.currency.equals("JOD")
            || !to.currency.equals("JOD")) error = "ACCOUNT_NOT_ACTIVE";
        else if (p.amount().compareTo(from.limit) > 0) error = "PAYMENT_LIMIT_EXCEEDED";
        else if (from.balance.compareTo(p.amount()) < 0) error = "INSUFFICIENT_FUNDS";
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (error == null) {
          BigDecimal debit = from.balance.subtract(p.amount()), credit = to.balance.add(p.amount());
          update(c, from.id, debit);
          hook.accept("AFTER_DEBIT");
          update(c, to.id, credit);
          ledger(c, p.paymentId(), from.id, "DEBIT", p.amount().negate(), debit);
          ledger(c, p.paymentId(), to.id, "CREDIT", p.amount(), credit);
          hook.accept("AFTER_LEDGER");
        }
        try (var s =
            c.prepareStatement(
                "UPDATE payments SET status=?,rejection_code=?,processed_at=? WHERE"
                    + " payment_id=?")) {
          s.setString(1, error == null ? "COMPLETED" : "REJECTED");
          s.setString(2, error);
          s.setTimestamp(3, Timestamp.from(now));
          s.setObject(4, p.paymentId());
          s.executeUpdate();
        }
        Outcome result =
            new Outcome(
                p.paymentId(),
                error == null ? "COMPLETED" : "REJECTED",
                p.fromAccount(),
                p.toAccount(),
                p.amount().toPlainString(),
                "JOD",
                now,
                error,
                false);
        c.commit();
        return result;
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        if (e instanceof SQLException sql) throw sql;
        if (e instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(e);
      }
    }
  }

  private void metric(String name, boolean record) {
    if (record)
      try {
        io.micrometer.core.instrument.Metrics.counter(name).increment();
      } catch (Exception ignored) {
      }
  }

  private record Account(
      String id,
      BigDecimal balance,
      String status,
      String currency,
      BigDecimal limit,
      boolean receives) {}

  public Outcome get(UUID id, Caller user) {
    return repository.get(id, user);
  }

  public Map<String, String> balance(String id, Caller user) {
    return repository.balance(id, user);
  }

  public List<String> reconcile() {
    return repository.reconcile();
  }

  public boolean rateAllowed(String principal, int limit) {
    return repository.rateAllowed(principal, limit);
  }
}
