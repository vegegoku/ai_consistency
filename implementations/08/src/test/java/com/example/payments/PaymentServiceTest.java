package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.*;
import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.persistence.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class PaymentServiceTest {
  static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  static DataSource data;
  PaymentService service;

  @BeforeAll
  static void setup() {
    db.start();
    var d = new PGSimpleDataSource();
    d.setURL(db.getJdbcUrl());
    d.setUser(db.getUsername());
    d.setPassword(db.getPassword());
    data = d;
    Flyway.configure().dataSource(data).load().migrate();
  }

  @AfterAll
  static void stop() {
    db.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_debit_authorizations,accounts,customers"
              + " CASCADE");
      s.execute("INSERT INTO customers(id,display_name) VALUES ('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES"
              + " ('A','alice',500.000,500.000),('B','bob',100.000,100.000)");
    }
    service = new PaymentService(new PaymentStore(data), new MessageCodec(), FaultInjector.NONE);
  }

  PaymentCommand command(String amount) {
    return new PaymentCommand(
        UUID.randomUUID(),
        "A",
        "B",
        new BigDecimal(amount).setScale(3),
        Instant.now(),
        "Invoice",
        "");
  }

  BigDecimal balance(String id) throws Exception {
    try (var c = data.getConnection();
        var s = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      s.setString(1, id);
      var r = s.executeQuery();
      r.next();
      return r.getBigDecimal(1);
    }
  }

  long count(String table) throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void transfersExactlyAndReplaysOriginalAfterRestart() throws Exception {
    var p = command("125.750");
    var first = service.transfer(p, "alice", "cid");
    assertEquals(201, first.http());
    assertEquals("125.750", first.body().get("amount"));
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    var restarted =
        new PaymentService(new PaymentStore(data), new MessageCodec(), FaultInjector.NONE);
    var replay = restarted.transfer(p, "alice", "other");
    assertEquals(200, replay.http());
    assertEquals(first.body(), replay.body());
    assertEquals(2, count("ledger_entries"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void conflictingPayloadCannotMoveMoneyTwice() throws Exception {
    var p = command("1");
    service.transfer(p, "alice", "x");
    var changed =
        new PaymentCommand(
            p.paymentId(), "A", "B", new BigDecimal("2.000"), p.date(), p.purpose(), p.notes());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiFailure.class, () -> service.transfer(changed, "alice", "x")).code);
    assertEquals(new BigDecimal("499.000"), balance("A"));
  }

  @Test
  void durableBusinessRejectionDoesNotMoveMoney() throws Exception {
    var p = command("501");
    var failed = service.transfer(p, "alice", "x");
    assertEquals("INSUFFICIENT_FUNDS", failed.body().get("code"));
    assertEquals(failed.body(), service.transfer(p, "alice", "y").body());
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void cannotDebitUnownedAccountOrReadOthers() throws Exception {
    assertEquals(
        403, assertThrows(ApiFailure.class, () -> service.transfer(command("1"), "bob", "x")).http);
    assertEquals(0, count("payments"));
    assertEquals(404, assertThrows(ApiFailure.class, () -> service.balance("A", "bob")).http);
    var p = command("1");
    service.transfer(p, "alice", "x");
    assertEquals(
        404, assertThrows(ApiFailure.class, () -> service.find(p.paymentId(), "mallory")).http);
    assertEquals(
        403, assertThrows(ApiFailure.class, () -> service.transfer(p, "mallory", "x")).http);
  }

  @Test
  void frozenDestinationAndAccountRestrictionsReject() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    }
    assertEquals(
        "ACCOUNT_NOT_ACTIVE", service.transfer(command("1"), "alice", "x").body().get("code"));
  }

  @Test
  void injectedPostDebitFailureRollsBackEverything() throws Exception {
    var broken =
        new PaymentService(
            new PaymentStore(data),
            new MessageCodec(),
            () -> {
              throw new IllegalStateException("injected");
            });
    assertThrows(IllegalStateException.class, () -> broken.transfer(command("10"), "alice", "x"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void concurrentDuplicatesMoveExactlyOnce() throws Exception {
    var p = command("50");
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<Outcome>>();
      for (int i = 0; i < 24; i++)
        tasks.add(() -> service.transfer(p, "alice", UUID.randomUUID().toString()));
      var results = pool.invokeAll(tasks);
      long created = 0;
      for (var f : results) if (f.get().http() == 201) created++;
      assertEquals(1, created);
    }
    assertEquals(new BigDecimal("450.000"), balance("A"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentPaymentsNeverOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<Outcome>>();
      for (int i = 0; i < 20; i++) tasks.add(() -> service.transfer(command("50"), "alice", "x"));
      for (var f : pool.invokeAll(tasks)) assertTrue(Set.of(201, 409).contains(f.get().http()));
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void ledgerAndFinalPaymentsAreImmutable() throws Exception {
    service.transfer(command("1"), "alice", "x");
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class, () -> s.execute("UPDATE ledger_entries SET signed_amount=0"));
      assertThrows(SQLException.class, () -> s.execute("DELETE FROM payments"));
    }
  }

  @Test
  void reconciliationDetectsCorruptedBalance() throws Exception {
    service.transfer(command("1"), "alice", "x");
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET available_balance=available_balance+1 WHERE id='B'");
    }
    assertFalse(service.reconcile().isEmpty());
  }

  @Test
  void unknownCommitOutcomeCanBeResolvedByLookup() throws Exception {
    var p = command("2");
    service.transfer(p, "alice", "x");
    assertEquals("COMPLETED", service.find(p.paymentId(), "alice").body().get("status"));
    assertEquals(200, service.transfer(p, "alice", "x").http());
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void oppositeDirectionTransfersUseDeterministicLocks() throws Exception {
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<Outcome>>();
      for (int i = 0; i < 20; i++) {
        final boolean reverse = i % 2 == 0;
        tasks.add(
            () ->
                service.transfer(
                    new PaymentCommand(
                        UUID.randomUUID(),
                        reverse ? "B" : "A",
                        reverse ? "A" : "B",
                        new BigDecimal("1.000"),
                        Instant.now(),
                        "Opposite",
                        ""),
                    reverse ? "bob" : "alice",
                    "x"));
      }
      for (var f : pool.invokeAll(tasks)) assertEquals(201, f.get().http());
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void lockTimeoutHasBoundedRetriesAndNoDurableRejection() throws Exception {
    try (var held = data.getConnection()) {
      held.setAutoCommit(false);
      try (var s = held.createStatement()) {
        s.execute("SELECT id FROM accounts WHERE id='A' FOR UPDATE");
      }
      long started = System.nanoTime();
      assertEquals(
          503,
          assertThrows(ApiFailure.class, () -> service.transfer(command("1"), "alice", "x")).http);
      assertTrue((System.nanoTime() - started) < java.time.Duration.ofSeconds(15).toNanos());
      held.rollback();
    }
    assertEquals(0, count("payments"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void ledgerInsertFailureRollsBackDebitCreditAndPayment() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE FUNCTION injected_ledger_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " RAISE EXCEPTION 'injected' USING ERRCODE='23514'; END $$");
      s.execute(
          "CREATE TRIGGER injected_ledger_fault BEFORE INSERT ON ledger_entries FOR EACH ROW"
              + " EXECUTE FUNCTION injected_ledger_fault()");
    }
    try {
      assertEquals(
          500,
          assertThrows(ApiFailure.class, () -> service.transfer(command("2"), "alice", "x")).http);
      assertEquals(new BigDecimal("500.000"), balance("A"));
      assertEquals(new BigDecimal("100.000"), balance("B"));
      assertEquals(0, count("payments"));
      assertEquals(0, count("ledger_entries"));
    } finally {
      try (var c = data.getConnection();
          var s = c.createStatement()) {
        s.execute("DROP TRIGGER injected_ledger_fault ON ledger_entries");
        s.execute("DROP FUNCTION injected_ledger_fault()");
      }
    }
  }

  @Test
  void delegatedDebitAndAccountLimitAreEnforced() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute("INSERT INTO account_debit_authorizations VALUES ('A','agent')");
      s.execute("UPDATE accounts SET transfer_limit=2 WHERE id='A'");
    }
    assertEquals(201, service.transfer(command("1"), "agent", "x").http());
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED", service.transfer(command("3"), "agent", "x").body().get("code"));
  }

  @Test
  void reconciliationDoesNotLeakReadOnlyConnectionState() throws Exception {
    assertTrue(service.reconcile().isEmpty());
    assertEquals(201, service.transfer(command("1"), "alice", "x").http());
  }
}
