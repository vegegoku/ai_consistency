package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.application.PaymentService;
import com.example.payments.application.TransferProbe;
import com.example.payments.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
class PaymentIntegrationTest {
  @Inject PaymentService service;
  @Inject DataSource db;
  @Inject FailingProbe probe;
  final Caller alice =
      new Caller("alice", Set.of("payments:write", "payments:read", "payments:admin"));

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class FailingProbe extends TransferProbe {
    final AtomicBoolean fail = new AtomicBoolean();

    public void failNext() {
      fail.set(true);
    }

    @Override
    public void afterDebit() {
      if (fail.getAndSet(false)) throw new IllegalStateException("injected failure");
      super.afterDebit();
    }
  }

  @BeforeEach
  void seed() throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      s.execute("TRUNCATE ledger_entries,payments,accounts,customers CASCADE");
      s.execute(
          "INSERT INTO customers(id,display_name,status) VALUES"
              + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
              + " VALUES ('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
    }
  }

  PaymentCommand command(UUID id, String amount) {
    return new MessageParser()
        .parse(
            ("{\"paymentId\":\""
                    + id
                    + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\""
                    + amount
                    + "\",\"date\":\""
                    + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    + "\",\"purpose\":\"test\"}")
                .getBytes(),
            false);
  }

  String scalar(String sql) throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      r.next();
      return r.getString(1);
    }
  }

  @Test
  void exactAccountingReplayAndConflict() throws Exception {
    var c = command(UUID.randomUUID(), "125.750");
    assertEquals(201, service.transfer(c, alice, "test").httpStatus());
    assertEquals("374.250", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertEquals("225.750", scalar("SELECT available_balance FROM accounts WHERE id='B'"));
    assertEquals("2", scalar("SELECT count(*) FROM ledger_entries"));
    assertEquals("0.000", scalar("SELECT sum(signed_amount) FROM ledger_entries"));
    assertEquals(200, service.transfer(c, alice, "test").httpStatus());
    var changed =
        new PaymentCommand(
            c.paymentId(),
            c.fromAccount(),
            c.toAccount(),
            new java.math.BigDecimal("1.000"),
            c.date(),
            c.purpose(),
            c.notes());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> service.transfer(changed, alice, "test")).code());
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void durableRejectionAndUnauthorizedCaller() throws Exception {
    var c = command(UUID.randomUUID(), "600");
    var first = service.transfer(c, alice, "test");
    assertEquals(409, first.httpStatus());
    assertEquals(first.body(), service.transfer(c, alice, "another").body());
    assertEquals("500.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertEquals(
        403,
        service
            .transfer(command(UUID.randomUUID(), "1"), new Caller("bob", alice.scopes()), "test")
            .httpStatus());
    assertEquals("0", scalar("SELECT count(*) FROM ledger_entries"));
  }

  @Test
  void concurrentDuplicateMovesMoneyOnce() throws Exception {
    var c = command(UUID.randomUUID(), "100");
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++) tasks.add(() -> service.transfer(c, alice, "test").httpStatus());
      var results = pool.invokeAll(tasks);
      long created = 0;
      for (var r : results) {
        int status = r.get();
        assertTrue(status == 201 || status == 200);
        if (status == 201) created++;
      }
      assertEquals(1, created);
    }
    assertEquals("400.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
  }

  @Test
  void concurrentSpendCannotOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        var c = command(UUID.randomUUID(), "100");
        tasks.add(() -> service.transfer(c, alice, "test").httpStatus());
      }
      int good = 0;
      for (var r : pool.invokeAll(tasks)) if (r.get() == 201) good++;
      assertEquals(5, good);
    }
    assertEquals("0.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void failureAfterDebitRollsEverythingBack() throws Exception {
    probe.failNext();
    assertThrows(
        RuntimeException.class,
        () -> service.transfer(command(UUID.randomUUID(), "100"), alice, "test"));
    assertEquals("500.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertEquals("100.000", scalar("SELECT available_balance FROM accounts WHERE id='B'"));
    assertEquals("0", scalar("SELECT count(*) FROM payments"));
    assertEquals("0", scalar("SELECT count(*) FROM ledger_entries"));
  }

  @Test
  void reconciliationDetectsCorruptBaseline() throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET available_balance=501 WHERE id='A'");
    }
    assertFalse(service.reconcile().isEmpty());
  }

  @Test
  void anonymousRequestsAreDenied() {
    given()
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"payments:write", "payments:read"})
  void jsonAndXmlApiAndNegotiation() {
    String id = UUID.randomUUID().toString();
    String date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>test</purpose></payment>";
    given()
        .contentType("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("amount", equalTo("125.750"));
    given()
        .contentType("application/json")
        .body(
            Map.of(
                "paymentId",
                id,
                "fromAccount",
                "A",
                "toAccount",
                "B",
                "amount",
                "125.750",
                "date",
                date,
                "purpose",
                "test"))
        .post("/api/v1/payments")
        .then()
        .statusCode(200)
        .body("amount", equalTo("125.750"));
    given()
        .accept("application/xml")
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .contentType("application/xml");
    given()
        .contentType("application/*")
        .body(new byte[] {120})
        .post("/api/v1/payments")
        .then()
        .statusCode(415);
    given().contentType("text/plain").body("x").post("/api/v1/payments").then().statusCode(415);
    given().accept("text/html").get("/api/v1/accounts/A/balance").then().statusCode(406);
    given().get("/api/v1/accounts/B/balance").then().statusCode(404);
  }

  @Test
  void accountPoliciesRejectWithoutBalanceChanges() throws Exception {
    for (String status : List.of("FROZEN", "CLOSED")) {
      try (var c = db.getConnection();
          var statement = c.createStatement()) {
        statement.execute("UPDATE accounts SET status='" + status + "' WHERE id='A'");
      }
      assertEquals(
          "ACCOUNT_NOT_ACTIVE",
          service.transfer(command(UUID.randomUUID(), "1"), alice, "test").body().get("code"));
    }
    try (var c = db.getConnection();
        var statement = c.createStatement()) {
      statement.execute("UPDATE accounts SET status='ACTIVE',transfer_limit=1 WHERE id='A'");
    }
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED",
        service.transfer(command(UUID.randomUUID(), "2"), alice, "test").body().get("code"));
    var p = command(UUID.randomUUID(), "1");
    assertEquals(
        "FORBIDDEN",
        service
            .transfer(
                new PaymentCommand(
                    p.paymentId(),
                    p.fromAccount(),
                    "ABSENT",
                    p.amount(),
                    p.date(),
                    p.purpose(),
                    p.notes()),
                alice,
                "test")
            .body()
            .get("code"));
    assertEquals("500.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertEquals("0", scalar("SELECT count(*) FROM ledger_entries"));
  }

  @Test
  void finalizedFinancialHistoryIsImmutable() throws Exception {
    service.transfer(command(UUID.randomUUID(), "1"), alice, "test");
    try (var c = db.getConnection();
        var statement = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () -> statement.execute("UPDATE ledger_entries SET signed_amount=signed_amount*2"));
      assertThrows(
          java.sql.SQLException.class,
          () -> statement.execute("UPDATE payments SET purpose='tampered'"));
      assertThrows(
          java.sql.SQLException.class, () -> statement.execute("DELETE FROM ledger_entries"));
    }
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void lockTimeoutIsBoundedAndLeavesNoPartialState() throws Exception {
    try (var c = db.getConnection();
        var statement = c.createStatement();
        var pool = Executors.newSingleThreadExecutor()) {
      c.setAutoCommit(false);
      statement.execute("SELECT id FROM accounts WHERE id='A' FOR UPDATE");
      var payment = command(UUID.randomUUID(), "1");
      var result =
          pool.submit(
              () ->
                  assertThrows(ApiException.class, () -> service.transfer(payment, alice, "test")));
      assertEquals("TEMPORARILY_UNAVAILABLE", result.get(12, TimeUnit.SECONDS).code());
      c.rollback();
    }
    assertEquals("500.000", scalar("SELECT available_balance FROM accounts WHERE id='A'"));
    assertEquals("0", scalar("SELECT count(*) FROM payments"));
    assertEquals("0", scalar("SELECT count(*) FROM ledger_entries"));
  }
}
