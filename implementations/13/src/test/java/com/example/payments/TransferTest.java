package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.persistence.Reconciliation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class TransferTest {
  static PostgreSQLContainer<?> pg;
  static PGSimpleDataSource ds;
  PaymentService service;

  @BeforeAll
  static void setup() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
    ds = new PGSimpleDataSource();
    ds.setURL(pg.getJdbcUrl());
    ds.setUser(pg.getUsername());
    ds.setPassword(pg.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
  }

  @AfterAll
  static void stop() {
    if (pg != null) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_windows"
              + " RESTART IDENTITY CASCADE");
      s.execute(
          "INSERT INTO customers(id,display_name,status) VALUES"
              + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      s.execute(
          "INSERT INTO"
              + " accounts(id,customer_id,opening_balance,available_balance,status,public_receive)"
              + " VALUES ('A','alice',500,500,'ACTIVE',true),('B','bob',100,100,'ACTIVE',true)");
    }
    service = new PaymentService(ds, new SimpleMeterRegistry(), () -> {});
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "Invoice", "");
  }

  PaymentService.Result transfer(PaymentCommand c) {
    return service.transfer(c, "alice", UUID.randomUUID().toString());
  }

  BigDecimal balance(String account) throws Exception {
    try (var c = ds.getConnection();
        var p = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      p.setString(1, account);
      try (var r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  long count(String table) throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void exactAccountingReplayConflictAndDurability() throws Exception {
    var cmd = command(UUID.randomUUID(), "125.750");
    var a = transfer(cmd);
    assertEquals(201, a.http());
    assertEquals("374.250", balance("A").toPlainString());
    assertEquals("225.750", balance("B").toPlainString());
    assertEquals(2, count("ledger_entries"));
    assertEquals(200, transfer(cmd).http());
    service = new PaymentService(ds, new SimpleMeterRegistry(), () -> {});
    assertEquals(a.body(), service.lookup(cmd.paymentId(), "alice").body());
    assertEquals(409, transfer(command(cmd.paymentId(), "1")).http());
    assertTrue(new Reconciliation(ds).report().isEmpty());
  }

  @Test
  void businessRejectionIsDurable() throws Exception {
    var cmd = command(UUID.randomUUID(), "900");
    assertEquals("INSUFFICIENT_FUNDS", transfer(cmd).body().get("code"));
    assertEquals("INSUFFICIENT_FUNDS", transfer(cmd).body().get("code"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, count("ledger_entries"));
    assertEquals(1, count("payments"));
  }

  @Test
  void failureAfterDebitRollsBack() throws Exception {
    service =
        new PaymentService(
            ds,
            new SimpleMeterRegistry(),
            () -> {
              throw new IllegalStateException("injected");
            });
    assertThrows(IllegalStateException.class, () -> transfer(command(UUID.randomUUID(), "100")));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void concurrencyPreventsOverdraftAndDuplicateEffects() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var same = command(UUID.randomUUID(), "100");
      var jobs = new ArrayList<Callable<PaymentService.Result>>();
      for (int i = 0; i < 8; i++) jobs.add(() -> transfer(same));
      for (var f : pool.invokeAll(jobs)) assertTrue(Set.of(200, 201).contains(f.get().http()));
      assertEquals(new BigDecimal("400.000"), balance("A"));
      assertEquals(2, count("ledger_entries"));
      jobs.clear();
      for (int i = 0; i < 8; i++) jobs.add(() -> transfer(command(UUID.randomUUID(), "100")));
      for (var f : pool.invokeAll(jobs)) assertTrue(Set.of(201, 409).contains(f.get().http()));
      assertEquals(new BigDecimal("0.000"), balance("A"));
      assertTrue(new Reconciliation(ds).report().isEmpty());
    }
  }

  @Test
  void unauthorizedCallerCannotReadOrDebit() {
    var cmd = command(UUID.randomUUID(), "1");
    assertEquals(403, service.transfer(cmd, "bob", UUID.randomUUID().toString()).http());
    assertThrows(ApiFailure.class, () -> service.lookup(cmd.paymentId(), "alice"));
    assertThrows(ApiFailure.class, () -> service.balance("A", "bob"));
  }

  @Test
  void frozenAccountAndPolicyLimitReject() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    }
    assertEquals(
        "ACCOUNT_NOT_ACTIVE", transfer(command(UUID.randomUUID(), "1")).body().get("code"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='ACTIVE',transfer_limit=1");
    }
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED", transfer(command(UUID.randomUUID(), "2")).body().get("code"));
  }

  @Test
  void reconciliationDetectsCorruptionAndLedgerIsImmutable() throws Exception {
    transfer(command(UUID.randomUUID(), "1"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class, () -> s.execute("UPDATE ledger_entries SET signed_amount=0"));
      s.execute("UPDATE accounts SET available_balance=0 WHERE id='B'");
    }
    assertFalse(new Reconciliation(ds).report().isEmpty());
  }

  @Test
  void rateLimitIsSharedByServiceInstances() {
    service.rateLimit("alice", 1);
    var second = new PaymentService(ds, new SimpleMeterRegistry(), () -> {});
    var failure = assertThrows(ApiFailure.class, () -> second.rateLimit("alice", 1));
    assertEquals(429, failure.http);
    assertEquals("RATE_LIMITED", failure.code);
  }

  @Test
  void ledgerPersistenceFailureRollsBackBothBalanceUpdates() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE FUNCTION inject_ledger_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " RAISE EXCEPTION 'injected ledger failure';END $$");
      s.execute(
          "CREATE TRIGGER inject_failure BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE"
              + " FUNCTION inject_ledger_failure()");
    }
    try {
      assertThrows(ApiFailure.class, () -> transfer(command(UUID.randomUUID(), "1")));
      assertEquals(new BigDecimal("500.000"), balance("A"));
      assertEquals(new BigDecimal("100.000"), balance("B"));
      assertEquals(0, count("payments"));
      assertEquals(0, count("ledger_entries"));
    } finally {
      try (var c = ds.getConnection();
          var s = c.createStatement()) {
        s.execute("DROP TRIGGER inject_failure ON ledger_entries");
        s.execute("DROP FUNCTION inject_ledger_failure()");
      }
    }
  }

  @Test
  void oppositeDirectionTransfersUseConsistentLocks() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Callable<PaymentService.Result>>();
      for (int i = 0; i < 10; i++) {
        jobs.add(() -> transfer(command(UUID.randomUUID(), "1")));
        jobs.add(
            () ->
                service.transfer(
                    new PaymentCommand(
                        UUID.randomUUID(),
                        "B",
                        "A",
                        new BigDecimal("1.000"),
                        Instant.now(),
                        "Return",
                        ""),
                    "bob",
                    UUID.randomUUID().toString()));
      }
      for (var job : pool.invokeAll(jobs)) assertEquals(201, job.get().http());
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertEquals(40, count("ledger_entries"));
    assertTrue(new Reconciliation(ds).report().isEmpty());
  }
}
