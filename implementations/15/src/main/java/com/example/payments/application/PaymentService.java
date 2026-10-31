package com.example.payments.application;

import com.example.payments.api.ErrorBodies;
import com.example.payments.domain.*;
import com.example.payments.persistence.AccountRepository;
import com.example.payments.persistence.AccountRepository.Account;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
  private final JdbcTemplate db;
  private final AccountRepository accounts;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final int retries;

  public PaymentService(
      JdbcTemplate db,
      AccountRepository accounts,
      PlatformTransactionManager manager,
      ObjectMapper json,
      Clock clock,
      MeterRegistry metrics,
      @Value("${payments.max-retries:2}") int retries) {
    this.db = db;
    this.accounts = accounts;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(10);
    this.json = json;
    this.clock = clock;
    this.metrics = metrics;
    this.retries = retries;
  }

  public Outcome transfer(PaymentCommand command, String principal, String correlationId) {
    long start = System.nanoTime();
    String correlation = uuidOrRandom(correlationId);
    for (int attempt = 0; ; attempt++) {
      try {
        Outcome outcome =
            Objects.requireNonNull(tx.execute(status -> execute(command, principal, correlation)));
        // Deliberately after TransactionTemplate returned: telemetry cannot alter a committed
        // financial outcome.
        try {
          metrics
              .counter(
                  outcome.replay() ? "payment.replays" : "payment.outcomes",
                  "code",
                  Objects.toString(
                      outcome.body().getOrDefault("code", outcome.body().get("status"))))
              .increment();
          metrics
              .timer("payment.processing")
              .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
          log.atInfo()
              .addKeyValue("paymentId", command.paymentId())
              .addKeyValue("correlationId", correlation)
              .addKeyValue("status", outcome.body().get("status"))
              .addKeyValue("code", outcome.body().getOrDefault("code", "COMPLETED"))
              .addKeyValue("latencyMs", (System.nanoTime() - start) / 1_000_000)
              .log("Payment outcome committed");
        } catch (RuntimeException ignored) {
          /* Committed financial outcome takes precedence over telemetry. */
        }
        return outcome;
      } catch (PaymentException e) {
        throw e;
      } catch (RuntimeException e) {
        String state = sqlState(e);
        boolean transientFailure = Set.of("40001", "40P01", "55P03", "57014").contains(state);
        if (state.equals("55P03") || state.equals("57014")) safeMetric("payment.lock.timeouts");
        if (transientFailure && attempt < retries) {
          safeMetric("payment.retries");
          try {
            Thread.sleep(25L * (attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable(command.paymentId());
          }
          continue;
        }
        if (transientFailure
            || e instanceof org.springframework.transaction.CannotCreateTransactionException
            || e instanceof org.springframework.transaction.TransactionSystemException
            || e instanceof DataAccessException
            || state.startsWith("08")
            || state.startsWith("53")
            || state.startsWith("57")) {
          throw unavailable(command.paymentId());
        }
        safeMetric("payment.unexpected.failures");
        throw new PaymentException(
            500,
            "INTERNAL_ERROR",
            "The payment outcome could not be confirmed; query or retry using the same payment ID.",
            List.of(),
            command.paymentId());
      }
    }
  }

  /** A read-only fallback when mutable admission policy no longer accepts a historical retry. */
  public Optional<Outcome> replayIfStored(PaymentCommand command, String principal) {
    var rows =
        db.queryForList(
            "SELECT payload_fingerprint,http_status,receipt::text FROM payments WHERE payment_id=?"
                + " AND requested_by=?",
            command.paymentId(),
            principal);
    if (rows.isEmpty()) return Optional.empty();
    var stored = rows.getFirst();
    if (!command.fingerprint().equals(stored.get("payload_fingerprint")))
      throw new PaymentException(
          409,
          "IDEMPOTENCY_CONFLICT",
          "Payment ID has already been used for another instruction.",
          List.of(),
          command.paymentId());
    int status = ((Number) stored.get("http_status")).intValue();
    safeMetric("payment.replays");
    return Optional.of(
        new Outcome(status == 201 ? 200 : status, read(stored.get("receipt").toString()), true));
  }

  private Outcome execute(PaymentCommand c, String principal, String correlation) {
    db.execute("SET LOCAL lock_timeout='2s'");
    db.execute("SET LOCAL statement_timeout='5s'");
    int inserted =
        db.update(
            "INSERT INTO"
                + " payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id)"
                + " VALUES(?,?,?,?,?,?,?,?, 'PROCESSING',?,?::uuid) ON CONFLICT(payment_id) DO"
                + " NOTHING",
            c.paymentId(),
            c.fromAccount(),
            c.toAccount(),
            c.amount(),
            Timestamp.from(c.date()),
            c.purpose(),
            c.notes(),
            c.fingerprint(),
            principal,
            correlation);
    if (inserted == 0) {
      var existing =
          db.queryForMap(
              "SELECT payload_fingerprint,requested_by,http_status,receipt::text FROM payments"
                  + " WHERE payment_id=?",
              c.paymentId());
      if (!principal.equals(existing.get("requested_by")))
        throw new PaymentException(
            403, "FORBIDDEN", "The payment could not be completed.", List.of(), c.paymentId());
      if (!c.fingerprint().equals(existing.get("payload_fingerprint")))
        throw new PaymentException(
            409,
            "IDEMPOTENCY_CONFLICT",
            "Payment ID has already been used for another instruction.",
            List.of(),
            c.paymentId());
      int http = ((Number) existing.get("http_status")).intValue();
      return new Outcome(http == 201 ? 200 : http, read(existing.get("receipt").toString()), true);
    }
    List<Account> locked = accounts.lock(c.fromAccount(), c.toAccount());
    Account
        from = locked.stream().filter(a -> a.id().equals(c.fromAccount())).findFirst().orElse(null),
        to = locked.stream().filter(a -> a.id().equals(c.toAccount())).findFirst().orElse(null);
    if (from == null || !accounts.authorized(c.fromAccount(), principal))
      return reject(c, "FORBIDDEN", 403, correlation);
    if (to == null)
      return reject(
          c, "FORBIDDEN", 403, correlation); // Never enumerate another customer's account.
    if (!from.customerStatus().equals("ACTIVE")
        || !to.customerStatus().equals("ACTIVE")
        || !from.status().equals("ACTIVE")
        || !to.status().equals("ACTIVE")) return reject(c, "ACCOUNT_NOT_ACTIVE", 409, correlation);
    if (!from.transfer()
        || !to.receive()
        || (!from.unrestricted() && !accounts.allowedDestination(c.fromAccount(), c.toAccount()))
        || !from.currency().equals("JOD")
        || !to.currency().equals("JOD")) return reject(c, "FORBIDDEN", 403, correlation);
    if (c.amount().compareTo(from.transferLimit()) > 0)
      return reject(c, "PAYMENT_LIMIT_EXCEEDED", 409, correlation);
    if (from.dailyLimit() != null) {
      BigDecimal spent =
          db.queryForObject(
              "SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND"
                  + " status='COMPLETED' AND processed_at>=date_trunc('day',now() AT TIME ZONE"
                  + " 'UTC') AT TIME ZONE 'UTC'",
              BigDecimal.class,
              from.id());
      if (spent.add(c.amount()).compareTo(from.dailyLimit()) > 0)
        return reject(c, "PAYMENT_LIMIT_EXCEEDED", 409, correlation);
    }
    if (from.balance().compareTo(c.amount()) < 0)
      return reject(c, "INSUFFICIENT_FUNDS", 409, correlation);
    BigDecimal debit = from.balance().subtract(c.amount()), credit = to.balance().add(c.amount());
    if (credit.precision() > 19) return reject(c, "PAYMENT_LIMIT_EXCEEDED", 409, correlation);
    accounts.balance(from.id(), debit);
    accounts.balance(to.id(), credit);
    ledger(c.paymentId(), from.id(), "DEBIT", c.amount().negate(), debit);
    ledger(c.paymentId(), to.id(), "CREDIT", c.amount(), credit);
    Instant now = clock.instant();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paymentId", c.paymentId().toString());
    body.put("status", "COMPLETED");
    body.put("fromAccount", c.fromAccount());
    body.put("toAccount", c.toAccount());
    body.put("amount", c.amount().toPlainString());
    body.put("currency", "JOD");
    body.put("processedAt", now.toString());
    finish(c.paymentId(), "COMPLETED", null, 201, body, now);
    return new Outcome(201, body, false);
  }

  private Outcome reject(PaymentCommand c, String code, int http, String correlation) {
    Instant now = clock.instant();
    var error =
        new PaymentException(
            http,
            code,
            "The payment could not be completed.",
            List.of(
                new Issue(
                    code.equals("INSUFFICIENT_FUNDS") ? "amount" : "payment",
                    code,
                    "The transfer does not satisfy account or payment policy.")),
            c.paymentId());
    var body = ErrorBodies.create(error, correlation, now);
    body.put("status", "REJECTED");
    body = read(write(body));
    finish(c.paymentId(), "REJECTED", code, http, body, now);
    db.update(
        "INSERT INTO payment_issues(payment_id,code,field,safe_message,correlation_id)"
            + " VALUES(?,?,?,'The payment could not be completed.',?::uuid)",
        c.paymentId(),
        code,
        "payment",
        correlation);
    return new Outcome(http, body, false);
  }

  private void finish(
      UUID id, String status, String code, int http, Map<String, Object> body, Instant now) {
    db.update(
        "UPDATE payments SET"
            + " status=?,rejection_code=?,http_status=?,receipt=?::jsonb,processed_at=? WHERE"
            + " payment_id=?",
        status,
        code,
        http,
        write(body),
        Timestamp.from(now),
        id);
  }

  private void ledger(UUID id, String account, String type, BigDecimal signed, BigDecimal after) {
    db.update(
        "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after)"
            + " VALUES(?,?,?,?,?)",
        id,
        account,
        type,
        signed,
        after);
  }

  public Map<String, Object> payment(UUID id, String principal) {
    var rows =
        db.queryForList(
            "SELECT receipt::text FROM payments p WHERE p.payment_id=? AND (p.requested_by=? OR"
                + " EXISTS(SELECT 1 FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE"
                + " a.id IN (p.from_account_id,p.to_account_id) AND c.subject=?))",
            id,
            principal,
            principal);
    if (rows.isEmpty())
      throw new PaymentException(404, "PAYMENT_NOT_FOUND", "The payment was not found.");
    return read(rows.getFirst().get("receipt").toString());
  }

  public Map<String, Object> balance(String id, String principal) {
    if (!accounts.authorized(id, principal))
      throw new PaymentException(404, "ACCOUNT_NOT_FOUND", "The account was not found.");
    BigDecimal amount =
        db.queryForObject(
            "SELECT available_balance FROM accounts WHERE id=?", BigDecimal.class, id);
    return Map.of(
        "accountId", id, "availableBalance", amount.setScale(3).toPlainString(), "currency", "JOD");
  }

  public record Reconciliation(
      boolean healthy,
      List<Map<String, Object>> paymentAnomalies,
      List<Map<String, Object>> balanceAnomalies,
      Instant checkedAt) {}

  public Reconciliation reconcile() {
    TransactionTemplate snapshot = new TransactionTemplate(tx.getTransactionManager());
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return snapshot.execute(
        s -> {
          var p =
              db.queryForList(
                  "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON"
                      + " l.payment_id=p.payment_id GROUP BY p.payment_id HAVING"
                      + " (p.status='COMPLETED' AND (count(l.id)<>2 OR"
                      + " coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE"
                      + " (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND"
                      + " l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND"
                      + " l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR"
                      + " (p.status<>'COMPLETED' AND count(l.id)<>0)");
          var a =
              db.queryForList(
                  "SELECT a.id AS"
                      + " account_id,a.available_balance,a.opening_balance+coalesce(sum(l.signed_amount),0)"
                      + " AS expected_balance FROM accounts a LEFT JOIN ledger_entries l ON"
                      + " l.account_id=a.id GROUP BY a.id HAVING"
                      + " a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)");
          return new Reconciliation(p.isEmpty() && a.isEmpty(), p, a, clock.instant());
        });
  }

  private Map<String, Object> read(String source) {
    try {
      return json.readValue(source, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Stored receipt is unreadable", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Receipt serialization failed", e);
    }
  }

  private String sqlState(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause())
      if (t instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
    return "";
  }

  private PaymentException unavailable(UUID id) {
    return new PaymentException(
        503,
        "TEMPORARILY_UNAVAILABLE",
        "Query or retry the payment using the same payment ID.",
        List.of(),
        id);
  }

  private String uuidOrRandom(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (Exception e) {
      return UUID.randomUUID().toString();
    }
  }

  private void safeMetric(String name) {
    try {
      metrics.counter(name).increment();
    } catch (RuntimeException ignored) {
    }
  }
}
