package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.*;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(DatabaseResource.class)
@TestSecurity(
    user = "alice",
    roles = {"user"})
@JwtSecurity(
    claims = {
      @Claim(key = "sub", value = "alice"),
      @Claim(key = "customer_id", value = "alice"),
      @Claim(key = "scope", value = "payments:write payments:read accounts:read")
    })
class PaymentTest {
  @Inject PaymentService service;
  @Inject AgroalDataSource db;
  static final Caller ALICE = new Caller("alice", "alice", false),
      BOB = new Caller("bob", "bob", false);

  @BeforeEach
  void reset() throws Exception {
    FailingHook.fail = false;
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,request_rates RESTART IDENTITY CASCADE");
      s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES('ACC-1001','alice',500,500,'ACTIVE'),('ACC-2002','bob',100,100,'ACTIVE')");
    }
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id,
        "ACC-1001",
        "ACC-2002",
        new BigDecimal(amount).setScale(3),
        Instant.now().minusSeconds(2),
        "Invoice",
        null);
  }

  String json(UUID id, String amount, String date) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"
        + amount
        + ",\"date\":\""
        + date
        + "\",\"purpose\":\"Invoice\"}";
  }

  String xml(UUID id, String amount, String date) {
    return "<payment><paymentId>"
        + id
        + "</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>"
        + amount
        + "</amount><date>"
        + date
        + "</date><purpose>Invoice</purpose></payment>";
  }

  void balances(String from, String to) {
    assertEquals(from, service.balance("ACC-1001", ALICE).get("availableBalance"));
    assertEquals(to, service.balance("ACC-2002", BOB).get("availableBalance"));
  }

  long count(String table) throws Exception {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void jsonSuccessAndCrossFormatReplay() {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    given()
        .contentType("application/json")
        .body(json(id, "125.750", date))
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .header("Location", endsWith(id.toString()))
        .body("amount", equalTo("125.750"));
    given()
        .contentType("application/xml")
        .body(xml(id, "125.75", date))
        .post("/api/v1/payments")
        .then()
        .statusCode(200)
        .body("amount", equalTo("125.750"));
    balances("374.250", "225.750");
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void xmlSuccessAndXmlResponse() {
    given()
        .contentType("application/xml")
        .accept("application/xml")
        .body(xml(UUID.randomUUID(), "125.750", Instant.now().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("response.amount", equalTo("125.750"));
    balances("374.250", "225.750");
  }

  @Test
  void conflictAndOriginalReceipt() throws Exception {
    PaymentCommand p = command(UUID.randomUUID(), "10");
    var first = service.transfer(p, ALICE, UUID.randomUUID());
    assertEquals(first.body(), service.transfer(p, ALICE, UUID.randomUUID()).body());
    assertEquals(first.body(), service.get(p.paymentId(), ALICE).body());
    ApiFailure conflict =
        assertThrows(
            ApiFailure.class,
            () ->
                service.transfer(
                    new PaymentCommand(
                        p.paymentId(),
                        p.fromAccount(),
                        p.toAccount(),
                        new BigDecimal("11.000"),
                        p.date(),
                        p.purpose(),
                        p.notes()),
                    ALICE,
                    UUID.randomUUID()));
    assertEquals("IDEMPOTENCY_CONFLICT", conflict.code);
    balances("490.000", "110.000");
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void insufficientFundsRejectionIsDurable() {
    PaymentCommand p = command(UUID.randomUUID(), "999");
    var r = service.transfer(p, ALICE, UUID.randomUUID());
    assertEquals(409, r.http());
    assertEquals("INSUFFICIENT_FUNDS", r.body().get("code"));
    assertEquals(r.body(), service.transfer(p, ALICE, UUID.randomUUID()).body());
    assertEquals("REJECTED", service.get(p.paymentId(), ALICE).body().get("status"));
    balances("500.000", "100.000");
  }

  @Test
  void unauthorizedAndHiddenLookup() {
    PaymentCommand p = command(UUID.randomUUID(), "1");
    var r = service.transfer(p, BOB, UUID.randomUUID());
    assertEquals(403, r.http());
    assertEquals("FORBIDDEN", r.body().get("code"));
    balances("500.000", "100.000");
    assertEquals(404, assertThrows(ApiFailure.class, () -> service.get(p.paymentId(), ALICE)).http);
    assertEquals(404, assertThrows(ApiFailure.class, () -> service.balance("ACC-1001", BOB)).http);
  }

  @Test
  void frozenMissingAndRestrictedAccounts() throws Exception {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute("UPDATE accounts SET status='FROZEN' WHERE id='ACC-2002'");
    }
    assertEquals(
        "ACCOUNT_NOT_ACTIVE",
        service
            .transfer(command(UUID.randomUUID(), "1"), ALICE, UUID.randomUUID())
            .body()
            .get("code"));
    PaymentCommand p = command(UUID.randomUUID(), "1");
    assertEquals(
        "FORBIDDEN",
        service
            .transfer(
                new PaymentCommand(
                    p.paymentId(),
                    p.fromAccount(),
                    "MISSING",
                    p.amount(),
                    p.date(),
                    p.purpose(),
                    p.notes()),
                ALICE,
                UUID.randomUUID())
            .body()
            .get("code"));
    balances("500.000", "100.000");
  }

  @Test
  void rollbackAfterDebit() throws Exception {
    FailingHook.fail = true;
    PaymentCommand p = command(UUID.randomUUID(), "20");
    assertEquals(
        500,
        assertThrows(ApiFailure.class, () -> service.transfer(p, ALICE, UUID.randomUUID())).http);
    balances("500.000", "100.000");
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
    FailingHook.fail = false;
    assertEquals(201, service.transfer(p, ALICE, UUID.randomUUID()).http());
  }

  @Test
  void concurrentDuplicatesMoveFundsOnce() throws Exception {
    PaymentCommand p = command(UUID.randomUUID(), "25");
    try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        calls.add(() -> service.transfer(p, ALICE, UUID.randomUUID()).http());
      var results = pool.invokeAll(calls);
      int first = 0;
      for (var f : results) {
        int code = f.get();
        if (code == 201) first++;
        else assertEquals(200, code);
      }
      assertEquals(1, first);
    }
    balances("475.000", "125.000");
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentDistinctPaymentsNeverOverdraw() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        calls.add(
            () ->
                service
                    .transfer(command(UUID.randomUUID(), "100"), ALICE, UUID.randomUUID())
                    .http());
      int completed = 0;
      for (var f : pool.invokeAll(calls)) {
        if (f.get() == 201) completed++;
        else assertEquals(409, f.get());
      }
      assertEquals(5, completed);
    }
    balances("0.000", "600.000");
    assertEquals(10, count("ledger_entries"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void malformedAndFieldFailuresChangeNothing() {
    for (String body : List.of("{", "{\"amount\":1,\"amount\":2}", "{\"amount\":true}")) {
      given()
          .contentType("application/json")
          .body(body)
          .post("/api/v1/payments")
          .then()
          .statusCode(400)
          .body("code", equalTo("MALFORMED_MESSAGE"));
    }
    for (String amount : List.of("0", "-1", "1.0000", "10001")) {
      given()
          .contentType("application/json")
          .body(json(UUID.randomUUID(), amount, Instant.now().toString()))
          .post("/api/v1/payments")
          .then()
          .statusCode(400)
          .body("code", equalTo("VALIDATION_FAILED"));
    }
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("issues.size()", greaterThanOrEqualTo(6));
    balances("500.000", "100.000");
  }

  @Test
  void mediaNegotiationAndXxe() {
    given().contentType("text/plain").body("x").post("/api/v1/payments").then().statusCode(415);
    given()
        .contentType("application/json")
        .accept("text/html")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    given()
        .contentType("application/xml")
        .body(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM 'http://127.0.0.1:1/secret'>]><payment><notes>&x;</notes></payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400);
    balances("500.000", "100.000");
  }

  @Test
  @TestSecurity(authorizationEnabled = true)
  void unauthenticated() {
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }

  @Test
  void rateLimit() throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement s =
            c.prepareStatement("INSERT INTO request_rates VALUES('alice',?,600)")) {
      s.setLong(1, System.currentTimeMillis() / 60000);
      s.executeUpdate();
    }
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue());
  }

  @Test
  void reconciliationDetectsCorruptAccount() throws Exception {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute("UPDATE accounts SET available_balance=501 WHERE id='ACC-1001'");
    }
    assertEquals("ACCOUNT_BALANCE_MISMATCH", service.reconcile().getFirst().get("type"));
  }

  @Test
  void ledgerCannotBeMutated() throws Exception {
    service.transfer(command(UUID.randomUUID(), "2"), ALICE, UUID.randomUUID());
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      assertThrows(
          SQLException.class, () -> s.executeUpdate("UPDATE ledger_entries SET signed_amount=0"));
      assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM payments"));
    }
  }

  @Test
  void oversizedBodyReturnsStandardError() {
    given()
        .contentType("application/json")
        .body(" ".repeat(65537))
        .post("/api/v1/payments")
        .then()
        .statusCode(413)
        .body("code", equalTo("REQUEST_TOO_LARGE"));
  }

  @Test
  void dateAndSameAccountIssuesCollected() {
    given()
        .contentType("application/json")
        .body(json(UUID.randomUUID(), "-1", "2020-01-01T00:00:00").replace("ACC-2002", "ACC-1001"))
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("issues.size()", equalTo(3));
    balances("500.000", "100.000");
  }

  @Test
  void lockTimeoutDoesNotRecordRejection() throws Exception {
    PaymentCommand p = command(UUID.randomUUID(), "1");
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT * FROM accounts WHERE id='ACC-1001' FOR UPDATE");
      assertEquals(
          503,
          assertThrows(ApiFailure.class, () -> service.transfer(p, ALICE, UUID.randomUUID())).http);
      c.rollback();
    }
    assertEquals(0, count("payments"));
    balances("500.000", "100.000");
    assertEquals(201, service.transfer(p, ALICE, UUID.randomUUID()).http());
  }

  @Test
  void oppositeDirectionTransfersUseStableLocks() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        final boolean reverse = i % 2 == 0;
        calls.add(
            () -> {
              PaymentCommand p = command(UUID.randomUUID(), "1");
              return service
                  .transfer(
                      reverse
                          ? new PaymentCommand(
                              p.paymentId(),
                              p.toAccount(),
                              p.fromAccount(),
                              p.amount(),
                              p.date(),
                              p.purpose(),
                              p.notes())
                          : p,
                      reverse ? BOB : ALICE,
                      UUID.randomUUID())
                  .http();
            });
      }
      for (var f : pool.invokeAll(calls)) assertEquals(201, f.get());
    }
    balances("500.000", "100.000");
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void reconciliationDetectsMissingLedgerFixture() throws Exception {
    service.transfer(command(UUID.randomUUID(), "1"), ALICE, UUID.randomUUID());
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_immutable");
      s.execute("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");
      s.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_immutable");
    }
    assertTrue(
        service.reconcile().stream()
            .anyMatch(x -> "PAYMENT_LEDGER_MISMATCH".equals(x.get("type"))));
  }
}
