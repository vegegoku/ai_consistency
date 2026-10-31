package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TransferTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  static PGSimpleDataSource ds;
  PaymentService service;

  @BeforeAll
  static void migrate() {
    ds = new PGSimpleDataSource();
    ds.setURL(db.getJdbcUrl());
    ds.setUser(db.getUsername());
    ds.setPassword(db.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
  }

  @BeforeEach
  void seed() throws Exception {
    sql(
        "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_limit_buckets"
            + " RESTART IDENTITY CASCADE");
    sql("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
    sql(
        "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status)"
            + " VALUES('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')");
    service = new PaymentService(ds);
  }

  void sql(String sql) throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  BigDecimal balance(String account) throws Exception {
    try (var c = ds.getConnection();
        var p = c.prepareStatement("select available_balance from accounts where id=?")) {
      p.setString(1, account);
      try (var r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id,
        "A",
        "B",
        new BigDecimal(amount).setScale(3),
        Instant.parse("2026-10-28T00:00:00Z"),
        "invoice",
        null);
  }

  PaymentResult transfer(PaymentCommand c) {
    return service.transfer(c, new Caller("alice", false), UUID.randomUUID().toString());
  }

  @Test
  void movesExactFundsAndReplaysOriginalReceipt() throws Exception {
    var c = command(UUID.randomUUID(), "125.750");
    var first = transfer(c);
    assertEquals(201, first.http());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(first.body(), transfer(c).body());
    assertEquals(200, transfer(c).http());
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void detectsConflictAndRetainsOriginal() throws Exception {
    var id = UUID.randomUUID();
    transfer(command(id, "10"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(PaymentException.class, () -> transfer(command(id, "20"))).code());
    assertEquals(new BigDecimal("490.000"), balance("A"));
  }

  @Test
  void recordsAndReplaysInsufficientFunds() throws Exception {
    var c = command(UUID.randomUUID(), "501");
    assertEquals("INSUFFICIENT_FUNDS", transfer(c).body().get("code"));
    sql("UPDATE accounts SET available_balance=1000 WHERE id='A'");
    assertEquals("INSUFFICIENT_FUNDS", transfer(c).body().get("code"));
    assertEquals(new BigDecimal("1000.000"), balance("A"));
  }

  @Test
  void rejectsUnauthorizedWithoutDisclosure() {
    var c = command(UUID.randomUUID(), "1");
    var result = service.transfer(c, new Caller("mallory", false), UUID.randomUUID().toString());
    assertEquals(403, result.http());
    assertThrows(
        PaymentException.class, () -> service.find(c.paymentId(), new Caller("alice", false)));
  }

  @Test
  void concurrentDuplicateTransfersOnce() throws Exception {
    var c = command(UUID.randomUUID(), "125.750");
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<PaymentResult>>();
      for (int i = 0; i < 12; i++) tasks.add(() -> transfer(c));
      var results = pool.invokeAll(tasks);
      int fresh = 0;
      for (var f : results) {
        var r = f.get();
        if (r.http() == 201) fresh++;
        else assertEquals(200, r.http());
      }
      assertEquals(1, fresh);
    }
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void concurrentDebitsNeverOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(10)) {
      var tasks = new ArrayList<Callable<PaymentResult>>();
      for (int i = 0; i < 10; i++) tasks.add(() -> transfer(command(UUID.randomUUID(), "100")));
      for (var f : pool.invokeAll(tasks)) assertTrue(Set.of(201, 409).contains(f.get().http()));
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void injectedCreditFailureRollsBackDebitAndPayment() throws Exception {
    sql(
        "CREATE FUNCTION fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='B'"
            + " THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    sql(
        "CREATE TRIGGER fail_credit BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION"
            + " fail_credit()");
    try {
      assertThrows(PaymentException.class, () -> transfer(command(UUID.randomUUID(), "100")));
      assertEquals(new BigDecimal("500.000"), balance("A"));
      assertEquals(new BigDecimal("100.000"), balance("B"));
      assertTrue(service.reconcile().isEmpty());
      try (var c = ds.getConnection();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT count(*) FROM payments")) {
        r.next();
        assertEquals(0, r.getInt(1));
      }
    } finally {
      sql("DROP TRIGGER fail_credit ON accounts");
      sql("DROP FUNCTION fail_credit()");
    }
  }

  @Test
  void restartAndLostResponseDiscoverOriginal() {
    var c = command(UUID.randomUUID(), "1");
    var first = transfer(c);
    service = new PaymentService(ds);
    assertEquals(first.body(), service.find(c.paymentId(), new Caller("alice", false)).body());
    assertEquals(first.body(), transfer(c).body());
  }

  @Test
  void detectsCorruptedAccount() throws Exception {
    transfer(command(UUID.randomUUID(), "1"));
    sql("UPDATE accounts SET available_balance=3 WHERE id='B'");
    assertFalse(service.reconcile().isEmpty());
  }

  @Test
  void immutableFinancialHistory() throws Exception {
    transfer(command(UUID.randomUUID(), "1"));
    assertThrows(SQLException.class, () -> sql("UPDATE ledger_entries SET signed_amount=0"));
    assertThrows(SQLException.class, () -> sql("DELETE FROM payments"));
  }

  @Test
  void openingBaselineCannotBeChanged() throws Exception {
    assertThrows(
        SQLException.class, () -> sql("UPDATE accounts SET opening_balance=0 WHERE id='A'"));
  }

  @Test
  void accountStateAndDailyPolicyRejectWithoutMovingMoney() throws Exception {
    sql("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    assertEquals(
        "ACCOUNT_NOT_ACTIVE", transfer(command(UUID.randomUUID(), "1")).body().get("code"));
    sql("UPDATE accounts SET status='ACTIVE',daily_limit=1 WHERE id='A'");
    sql("UPDATE accounts SET status='ACTIVE' WHERE id='B'");
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED", transfer(command(UUID.randomUUID(), "2")).body().get("code"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void boundedLockWaitReturnsTransientAndLeavesNoOutcome() throws Exception {
    var command = command(UUID.randomUUID(), "1");
    try (var connection = ds.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
        long started = System.nanoTime();
        assertEquals(
            "TEMPORARILY_UNAVAILABLE",
            assertThrows(PaymentException.class, () -> transfer(command)).code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
      } finally {
        connection.rollback();
      }
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertThrows(
        PaymentException.class,
        () -> service.find(command.paymentId(), new Caller("alice", false)));
  }

  @Test
  void databaseRestartPreservesCommittedOutcome() throws Exception {
    var command = command(UUID.randomUUID(), "1");
    var result = transfer(command);
    db.getDockerClient().restartContainerCmd(db.getContainerId()).exec();
    var state = db.getDockerClient().inspectContainerCmd(db.getContainerId()).exec();
    var bindings =
        state
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
    if (bindings != null && bindings.length > 0)
      ds.setURL(
          "jdbc:postgresql://"
              + db.getHost()
              + ":"
              + bindings[0].getHostPortSpec()
              + "/"
              + db.getDatabaseName());
    boolean available = false;
    for (int i = 0; i < 100; i++) {
      try (var c = ds.getConnection()) {
        available = c.isValid(1);
        if (available) break;
      } catch (SQLException e) {
        Thread.sleep(100);
      }
    }
    assertTrue(available, db.getLogs());
    service = new PaymentService(ds);
    assertEquals(
        result.body(), service.find(command.paymentId(), new Caller("alice", false)).body());
    assertEquals(new BigDecimal("499.000"), balance("A"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void rateLimitIsSharedAcrossServiceInstances() {
    var caller = new Caller("alice", false);
    service.rateLimit(caller, 2);
    new PaymentService(ds).rateLimit(caller, 2);
    assertEquals(
        "RATE_LIMITED",
        assertThrows(PaymentException.class, () -> service.rateLimit(caller, 2)).code());
  }
}
