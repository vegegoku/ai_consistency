package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.PaymentParser;
import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class DatabaseTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  PGSimpleDataSource ds;
  PaymentService service;
  final Caller alice = new Caller("alice", false);
  final Caller admin = new Caller("operator", true);

  @BeforeEach
  void setup() throws Exception {
    ds = new PGSimpleDataSource();
    ds.setURL(db.getJdbcUrl());
    ds.setUser(db.getUsername());
    ds.setPassword(db.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_buckets"
              + " CASCADE");
      s.execute("INSERT INTO customers(id,display_name)VALUES('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO"
              + " accounts(id,customer_id,currency,available_balance,opening_balance,status)VALUES('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
    }
    service = new PaymentService(ds);
  }

  PaymentCommand command(String amount) {
    return new PaymentCommand(
        UUID.randomUUID(),
        "A",
        "B",
        new BigDecimal(amount).setScale(3),
        Instant.now(),
        "Invoice",
        null);
  }

  Outcome send(PaymentCommand p) {
    return service.transfer(p, alice, UUID.randomUUID());
  }

  BigDecimal balance(String id) {
    return new BigDecimal(service.balance(id, admin).get("availableBalance"));
  }

  long scalar(String sql) throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void successfulTransferAndImmutableLedger() throws Exception {
    var result = send(command("125.750"));
    assertEquals("COMPLETED", result.status());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, scalar("SELECT count(*) FROM ledger_entries"));
    assertEquals(0, scalar("SELECT sum(signed_amount) FROM ledger_entries"));
    assertTrue(service.reconcile().isEmpty());
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      assertThrows(SQLException.class, () -> s.execute("DELETE FROM ledger_entries"));
      assertThrows(SQLException.class, () -> s.execute("UPDATE payments SET purpose='tampered'"));
    }
  }

  @Test
  void xmlUsesSameAccounting() {
    String x =
        "<payment><paymentId>"
            + UUID.randomUUID()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + Instant.now()
            + "</date><purpose>Invoice</purpose></payment>";
    send(new PaymentParser().parse(x.getBytes(), true));
    assertEquals(new BigDecimal("374.250"), balance("A"));
  }

  @Test
  void replayAndResponseLossNeverDoubleDebit() {
    var p = command("125.750");
    var original = send(p);
    var replay = send(p);
    assertTrue(replay.replay());
    assertEquals(original.processedAt(), replay.processedAt());
    assertEquals(new BigDecimal("374.250"), balance("A"));
  }

  @Test
  void conflictCannotMoveMoney() {
    var p = command("100");
    send(p);
    var other =
        new PaymentCommand(
            p.paymentId(),
            p.fromAccount(),
            p.toAccount(),
            new BigDecimal("101.000"),
            p.date(),
            p.purpose(),
            null);
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> send(other)).code);
    assertEquals(new BigDecimal("400.000"), balance("A"));
  }

  @Test
  void businessRejectionIsDurableAndReplayable() throws Exception {
    var p = command("501");
    var reject = send(p);
    assertEquals("INSUFFICIENT_FUNDS", reject.code());
    assertEquals(reject.processedAt(), send(p).processedAt());
    assertEquals("REJECTED", service.get(p.paymentId(), alice).status());
    assertEquals(0, scalar("SELECT count(*) FROM ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void unauthorizedCannotSubmitOrEnumerate() {
    var p = command("1");
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () -> service.transfer(p, new Caller("mallory", false), UUID.randomUUID()))
            .http);
    send(p);
    assertEquals(
        404,
        assertThrows(
                ApiException.class, () -> service.get(p.paymentId(), new Caller("mallory", false)))
            .http);
    assertEquals(
        404,
        assertThrows(ApiException.class, () -> service.balance("A", new Caller("bob", false)))
            .http);
  }

  @Test
  void inactiveMissingAndPolicyRestricted() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    }
    assertEquals("ACCOUNT_NOT_ACTIVE", send(command("1")).code());
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='ACTIVE',receive_enabled=false WHERE id='B'");
    }
    assertEquals("FORBIDDEN", send(command("1")).code());
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET receive_enabled=true,max_transfer=1 WHERE id='A' OR id='B'");
    }
    assertEquals("PAYMENT_LIMIT_EXCEEDED", send(command("2")).code());
    var p = command("1");
    assertEquals(
        "FORBIDDEN",
        send(new PaymentCommand(
                p.paymentId(), "A", "MISSING", p.amount(), p.date(), p.purpose(), null))
            .code());
  }

  @Test
  void concurrentDuplicatesTransferExactlyOnce() throws Exception {
    var p = command("10");
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Outcome>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) calls.add(() -> send(p));
      var results = executor.invokeAll(calls);
      int first = 0;
      for (var r : results) {
        var o = r.get();
        assertEquals("COMPLETED", o.status());
        if (!o.replay()) first++;
      }
      assertEquals(1, first);
    }
    assertEquals(new BigDecimal("490.000"), balance("A"));
    assertEquals(2, scalar("SELECT count(*) FROM ledger_entries"));
  }

  @Test
  void concurrentWithdrawalsCannotOverdraw() throws Exception {
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Outcome>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++) calls.add(() -> send(command("100")));
      var results = executor.invokeAll(calls);
      int complete = 0;
      for (var r : results) if (r.get().status().equals("COMPLETED")) complete++;
      assertEquals(5, complete);
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void oppositeDirectionLocksDoNotDeadlock() throws Exception {
    try (var executor = Executors.newFixedThreadPool(6)) {
      List<Callable<Outcome>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        int n = i;
        calls.add(
            () -> {
              var p = command("1");
              return service.transfer(
                  n % 2 == 0
                      ? p
                      : new PaymentCommand(
                          p.paymentId(), "B", "A", p.amount(), p.date(), p.purpose(), null),
                  admin,
                  UUID.randomUUID());
            });
      }
      for (var f : executor.invokeAll(calls)) assertEquals("COMPLETED", f.get().status());
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void injectedFailureAfterDebitRollsBackEveryRecord() throws Exception {
    var faulty =
        new PaymentService(
            ds,
            phase -> {
              if (phase.equals("AFTER_DEBIT")) throw new IllegalStateException("injected");
            });
    var p = command("10");
    assertThrows(IllegalStateException.class, () -> faulty.transfer(p, alice, UUID.randomUUID()));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, scalar("SELECT count(*) FROM payments"));
    assertEquals(0, scalar("SELECT count(*) FROM ledger_entries"));
    assertEquals("COMPLETED", send(p).status());
  }

  @Test
  void restartedServiceFindsCommittedReceipt() {
    var p = command("10");
    send(p);
    var restarted = new PaymentService(ds);
    assertEquals("COMPLETED", restarted.get(p.paymentId(), alice).status());
    assertTrue(restarted.transfer(p, alice, UUID.randomUUID()).replay());
  }

  @Test
  void reconciliationDetectsCorruption() throws Exception {
    send(command("1"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET available_balance=available_balance+1 WHERE id='A'");
    }
    assertTrue(service.reconcile().contains("ACCOUNT:A"));
  }

  @Test
  void rateLimitIsSharedAndBounded() {
    assertTrue(service.rateAllowed("alice", 2));
    assertTrue(new PaymentService(ds).rateAllowed("alice", 2));
    assertFalse(service.rateAllowed("alice", 2));
  }

  @Test
  void boundedLockTimeoutRollsBackProvisionalIdentity() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT id FROM accounts WHERE id='A' FOR UPDATE");
      long start = System.nanoTime();
      ApiException error = assertThrows(ApiException.class, () -> send(command("1")));
      assertEquals(503, error.http);
      assertEquals("TEMPORARILY_UNAVAILABLE", error.code);
      assertTrue((System.nanoTime() - start) < 10_000_000_000L);
      assertEquals(0, scalar("SELECT count(*) FROM payments"));
      c.rollback();
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void failureAfterLedgerRollsBackBothBalancesAndEntries() throws Exception {
    var faulty =
        new PaymentService(
            ds,
            phase -> {
              if (phase.equals("AFTER_LEDGER")) throw new IllegalStateException("injected");
            });
    assertThrows(
        IllegalStateException.class,
        () -> faulty.transfer(command("10"), alice, UUID.randomUUID()));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertEquals(0, scalar("SELECT count(*) FROM payments"));
    assertEquals(0, scalar("SELECT count(*) FROM ledger_entries"));
  }

  @Test
  void customerGrantAndDestinationRestriction() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "INSERT INTO"
              + " account_permissions(account_id,principal,can_debit)VALUES('A','delegate',true)");
      s.execute(
          "INSERT INTO"
              + " account_permissions(account_id,principal,can_receive)VALUES('B','delegate',false)");
    }
    var delegate = new Caller("delegate", false);
    assertEquals("FORBIDDEN", service.transfer(command("1"), delegate, UUID.randomUUID()).code());
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE account_permissions SET can_receive=true WHERE account_id='B'");
    }
    assertEquals("COMPLETED", service.transfer(command("1"), delegate, UUID.randomUUID()).status());
  }

  @Test
  void reconciliationDetectsMissingLedgerEntry() throws Exception {
    var payment = send(command("1"));
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      statement.execute("ALTER TABLE ledger_entries DISABLE TRIGGER immutable_ledger");
      statement.execute("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");
      statement.execute("ALTER TABLE ledger_entries ENABLE TRIGGER immutable_ledger");
    }
    assertTrue(service.reconcile().contains("PAYMENT:" + payment.paymentId()));
  }
}
