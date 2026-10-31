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
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class DatabaseTest {
  static PostgreSQLContainer<?> db;
  static PGSimpleDataSource ds;
  PaymentService service;

  @BeforeAll
  static void start() throws Exception {
    db = new PostgreSQLContainer<>("postgres:16-alpine");
    db.start();
    ds = new PGSimpleDataSource();
    ds.setURL(db.getJdbcUrl());
    ds.setUser(db.getUsername());
    ds.setPassword(db.getPassword());
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          new String(
              DatabaseTest.class.getResourceAsStream("/db/migration/V1__schema.sql").readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @AfterAll
  static void stop() {
    if (db != null) db.stop();
  }

  @BeforeEach
  void seed() throws Exception {
    service = new PaymentService(ds);
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("TRUNCATE ledger_entries,payments,account_permissions,accounts,customers CASCADE");
      s.execute("INSERT INTO customers(id,display_name) VALUES ('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
              + " VALUES ('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
      s.execute("INSERT INTO account_permissions VALUES ('B','alice',false,true)");
    }
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "invoice", null);
  }

  PaymentService.Result pay(PaymentCommand p) {
    return service.transfer(p, "alice", UUID.randomUUID().toString());
  }

  BigDecimal amount(String id) throws Exception {
    try (var c = ds.getConnection();
        var s = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      s.setString(1, id);
      try (var r = s.executeQuery()) {
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
  void jsonXmlParityAndReplay() throws Exception {
    var id = UUID.randomUUID();
    String date = Instant.now().toString();
    String json =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
            + date
            + "\",\"purpose\":\" invoice \"}";
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    var parser = new PaymentParser();
    var first = pay(parser.parse(json, "application/json"));
    assertEquals(201, first.http());
    assertEquals(first.receipt(), pay(parser.parse(xml, "application/xml")).receipt());
    assertEquals(new BigDecimal("374.250"), amount("A"));
    assertEquals(new BigDecimal("225.750"), amount("B"));
    assertEquals(2, count("ledger_entries"));
    assertEquals(true, service.reconcile().get("healthy"));
  }

  @Test
  void conflictsAndDurableRejection() throws Exception {
    var p = command(UUID.randomUUID(), "600");
    assertEquals("INSUFFICIENT_FUNDS", pay(p).receipt().get("code"));
    assertEquals(pay(p).receipt(), new PaymentService(ds).get(p.paymentId(), "alice").receipt());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiFailure.class, () -> pay(command(p.paymentId(), "1"))).code);
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), amount("A"));
  }

  @Test
  void concurrentDuplicatesExactlyOneEffect() throws Exception {
    var p = command(UUID.randomUUID(), "125.750");
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<PaymentService.Result>>();
      for (int i = 0; i < 16; i++) tasks.add(() -> pay(p));
      var results = pool.invokeAll(tasks);
      int created = 0;
      for (var f : results) {
        if (f.get().http() == 201) created++;
      }
      assertEquals(1, created);
    }
    assertEquals(2, count("ledger_entries"));
    assertEquals(new BigDecimal("374.250"), amount("A"));
  }

  @Test
  void concurrentDebitsCannotOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<PaymentService.Result>>();
      for (int i = 0; i < 10; i++) {
        var p = command(UUID.randomUUID(), "100");
        tasks.add(() -> pay(p));
      }
      int completed = 0;
      for (var f : pool.invokeAll(tasks)) if (f.get().http() == 201) completed++;
      assertEquals(5, completed);
    }
    assertEquals(new BigDecimal("0.000"), amount("A"));
    assertEquals(new BigDecimal("600.000"), amount("B"));
  }

  @Test
  void failureAfterDebitRollsEverythingBack() throws Exception {
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      statement.execute(
          "CREATE FUNCTION fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='B'"
              + " THEN RAISE EXCEPTION 'injected credit failure' USING ERRCODE='08006'; END IF;"
              + " RETURN NEW; END $$");
      statement.execute(
          "CREATE TRIGGER fail_credit BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION"
              + " fail_credit()");
    }
    var p = command(UUID.randomUUID(), "10");
    try {
      assertEquals(503, assertThrows(ApiFailure.class, () -> pay(p)).http);
      assertEquals(new BigDecimal("500.000"), amount("A"));
      assertEquals(new BigDecimal("100.000"), amount("B"));
      assertEquals(0, count("payments"));
      assertEquals(0, count("ledger_entries"));
    } finally {
      try (var c = ds.getConnection();
          var statement = c.createStatement()) {
        statement.execute("DROP TRIGGER fail_credit ON accounts");
        statement.execute("DROP FUNCTION fail_credit()");
      }
    }
    assertEquals(201, pay(p).http());
  }

  @Test
  void authorizationAndHiddenLookups() throws Exception {
    assertEquals(
        403,
        service
            .transfer(command(UUID.randomUUID(), "1"), "mallory", UUID.randomUUID().toString())
            .http());
    assertEquals(404, assertThrows(ApiFailure.class, () -> service.balance("A", "mallory")).http);
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void inactiveAndUnknownDestinations() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    }
    assertEquals("ACCOUNT_NOT_ACTIVE", pay(command(UUID.randomUUID(), "1")).receipt().get("code"));
    var p =
        new PaymentCommand(
            UUID.randomUUID(),
            "A",
            "missing",
            new BigDecimal("1.000"),
            Instant.now(),
            "invoice",
            null);
    assertEquals("FORBIDDEN", pay(p).receipt().get("code"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void receiptSurvivesServiceRestartAndResponseLoss() throws Exception {
    var p = command(UUID.randomUUID(), "10");
    var first = pay(p);
    var restarted = new PaymentService(ds);
    assertEquals(
        first.receipt(), restarted.transfer(p, "alice", UUID.randomUUID().toString()).receipt());
    assertEquals(first.receipt(), restarted.get(p.paymentId(), "alice").receipt());
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void reconciliationDetectsCorruption() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET available_balance=499 WHERE id='A'");
    }
    assertEquals(false, service.reconcile().get("healthy"));
    assertTrue(((List<?>) service.reconcile().get("inconsistentAccounts")).contains("A"));
  }

  @Test
  void immutableFinancialRecords() throws Exception {
    pay(command(UUID.randomUUID(), "1"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class,
          () -> s.execute("UPDATE ledger_entries SET signed_amount=-9 WHERE entry_type='DEBIT'"));
      assertThrows(SQLException.class, () -> s.execute("DELETE FROM payments"));
    }
  }
}
