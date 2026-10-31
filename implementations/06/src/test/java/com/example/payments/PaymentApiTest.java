package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.*;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
@TestSecurity(
    user = "alice",
    roles = {"user"})
@JwtSecurity(
    claims = {@Claim(key = "scope", value = "payments:submit payments:read accounts:read")})
class PaymentApiTest {
  @Inject DataSource db;
  @Inject PaymentService service;
  @Inject ReconciliationService reconciliation;

  @BeforeEach
  void reset() throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      s.execute("DROP TRIGGER IF EXISTS inject_failure ON ledger_entries");
      s.execute(
          "TRUNCATE"
              + " ledger_entries,payments,debit_grants,recipient_restrictions,accounts,customers,rate_limits"
              + " RESTART IDENTITY CASCADE");
      s.execute(
          "INSERT INTO customers(id,display_name,status)"
              + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
              + " VALUES('ACC-1001','alice','JOD',500,500,'ACTIVE'),('ACC-2002','bob','JOD',100,100,'ACTIVE'),('FROZEN','alice','JOD',100,100,'FROZEN')");
    }
  }

  String json(UUID id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now().toString()
        + "\",\"purpose\":\" Invoice \"}";
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

  long count(String table) throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  BigDecimal balance(String id) throws Exception {
    try (var c = db.getConnection();
        var p = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      p.setString(1, id);
      try (var r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  @Test
  void jsonSuccessReplayAndConflict() throws Exception {
    UUID id = UUID.randomUUID();
    String body = json(id, "125.750");
    String original =
        given()
            .contentType("application/json")
            .body(body)
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .header("Location", endsWith(id.toString()))
            .header("X-Correlation-ID", notNullValue())
            .body("amount", equalTo("125.750"))
            .extract()
            .asString();
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
    assertEquals(new BigDecimal("225.750"), balance("ACC-2002"));
    assertEquals(1, count("payments"));
    assertEquals(2, count("ledger_entries"));
    assertEquals(
        original,
        given()
            .contentType("application/json")
            .body(body)
            .post("/api/v1/payments")
            .then()
            .statusCode(200)
            .extract()
            .asString());
    given()
        .contentType("application/json")
        .body(body.replace("125.750", "126.000"))
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    given()
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
    assertEquals(true, reconciliation.report().get("balanced"));
  }

  @Test
  void xmlSuccessAndCrossFormatReplay() throws Exception {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    String xml = xml(id, "125.750", date);
    given()
        .contentType("application/xml")
        .accept("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("response.amount", equalTo("125.750"));
    String json =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.75\",\"date\":\""
            + date
            + "\",\"purpose\":\"Invoice\"}";
    given()
        .contentType("application/json")
        .body(json)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    assertEquals(2, count("ledger_entries"));
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
  }

  @Test
  void collectsValidationIssuesAndRejectsMalformed() throws Exception {
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"))
        .body("issues.size()", greaterThanOrEqualTo(6));
    for (String body :
        List.of("{", "{\"amount\":1,\"amount\":2}", "{\"unknown\":1}", "{\"fromAccount\":123}")) {
      given()
          .contentType("application/json")
          .body(body)
          .post("/api/v1/payments")
          .then()
          .statusCode(400)
          .body("code", equalTo("MALFORMED_MESSAGE"));
    }
    assertEquals(0, count("payments"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
  }

  @Test
  void amountPrecisionAndTimestampRules() throws Exception {
    for (String amount : List.of("0", "-1", "1.0001", "10000.001", "1.0000")) {
      given()
          .contentType("application/json")
          .body(json(UUID.randomUUID(), amount))
          .post("/api/v1/payments")
          .then()
          .statusCode(400)
          .body("code", equalTo("VALIDATION_FAILED"));
    }
    String old =
        json(UUID.randomUUID(), "1")
            .replaceFirst("[0-9]{4}-[0-9]{2}-[0-9]{2}T[^\"]+", "2020-01-01T00:00:00Z");
    given()
        .contentType("application/json")
        .body(old)
        .post("/api/v1/payments")
        .then()
        .statusCode(400);
    assertEquals(0, count("payments"));
  }

  @Test
  void rejectionIsDurableAndNeverMovesFunds() throws Exception {
    UUID id = UUID.randomUUID();
    String body = json(id, "501");
    given()
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("status", equalTo("REJECTED"))
        .body("code", equalTo("INSUFFICIENT_FUNDS"));
    given()
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("code", equalTo("INSUFFICIENT_FUNDS"));
    given()
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("REJECTED"));
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
  }

  @Test
  void accountRestrictionsAndHiddenLookups() throws Exception {
    given()
        .contentType("application/json")
        .body(
            json(UUID.randomUUID(), "1")
                .replace("ACC-1001", "ACC-2002")
                .replace("\"toAccount\":\"ACC-2002\"", "\"toAccount\":\"ACC-1001\""))
        .post("/api/v1/payments")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    given().get("/api/v1/accounts/ACC-2002/balance").then().statusCode(404);
    given()
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(200)
        .body("balance", equalTo("500.000"));
    given()
        .contentType("application/json")
        .body(json(UUID.randomUUID(), "1").replace("ACC-2002", "FROZEN"))
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("code", equalTo("ACCOUNT_NOT_ACTIVE"));
    given()
        .contentType("application/json")
        .body(json(UUID.randomUUID(), "1").replace("ACC-2002", "MISSING"))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void mediaAndXmlSecurity() throws Exception {
    given()
        .contentType("text/plain")
        .body("test")
        .post("/api/v1/payments")
        .then()
        .statusCode(415)
        .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    given()
        .contentType("application/json")
        .accept("text/html")
        .body(json(UUID.randomUUID(), "1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    for (String xml :
        List.of(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
            "<payment><amount>1</amount><amount>2</amount></payment>",
            "<payment><x/></payment>",
            "<payment xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include"
                + " href='file:///etc/passwd'/></payment>")) {
      given()
          .contentType("application/xml")
          .body(xml)
          .post("/api/v1/payments")
          .then()
          .statusCode(400)
          .body("code", equalTo("MALFORMED_MESSAGE"));
    }
    assertEquals(0, count("payments"));
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id,
        "ACC-1001",
        "ACC-2002",
        new BigDecimal(amount).setScale(3),
        Instant.now(),
        "Invoice",
        "");
  }

  @Test
  void concurrentDuplicatesHaveExactlyOneEffect() throws Exception {
    var cmd = command(UUID.randomUUID(), "125.750");
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<PaymentService.Outcome>> jobs = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        jobs.add(() -> service.transfer(cmd, "alice", UUID.randomUUID().toString()));
      var results = pool.invokeAll(jobs);
      int first = 0;
      for (var f : results) {
        var o = f.get();
        assertEquals("COMPLETED", o.status());
        if (!o.replay()) first++;
      }
      assertEquals(1, first);
    }
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
    assertEquals(1, count("payments"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentDebitsCannotOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<PaymentService.Outcome>> jobs = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        var cmd = command(UUID.randomUUID(), "100");
        jobs.add(() -> service.transfer(cmd, "alice", UUID.randomUUID().toString()));
      }
      var results = pool.invokeAll(jobs);
      int completed = 0;
      for (var f : results) if (f.get().status().equals("COMPLETED")) completed++;
      assertEquals(5, completed);
    }
    assertEquals(new BigDecimal("0.000"), balance("ACC-1001"));
    assertEquals(new BigDecimal("600.000"), balance("ACC-2002"));
    assertEquals(true, reconciliation.report().get("balanced"));
  }

  @Test
  void failureAfterBalanceUpdatesRollsBackEverything() throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "CREATE OR REPLACE FUNCTION test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
              + " RAISE EXCEPTION 'Injected failure';END $$");
      s.execute(
          "CREATE TRIGGER inject_failure BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE"
              + " FUNCTION test_fail()");
    }
    var cmd = command(UUID.randomUUID(), "125.750");
    var e =
        assertThrows(
            PaymentException.class,
            () -> service.transfer(cmd, "alice", UUID.randomUUID().toString()));
    assertEquals(500, e.http);
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(new BigDecimal("100.000"), balance("ACC-2002"));
  }

  @Test
  void lostResponseAndNewServiceConnectionFindOriginalOutcome() throws Exception {
    var cmd = command(UUID.randomUUID(), "10");
    var first = service.transfer(cmd, "alice", UUID.randomUUID().toString());
    var stored = service.find(cmd.paymentId(), "alice");
    assertEquals(first.processedAt(), stored.processedAt());
    var retry = service.transfer(cmd, "alice", UUID.randomUUID().toString());
    assertTrue(retry.replay());
    assertEquals(first.processedAt(), retry.processedAt());
    assertEquals(new BigDecimal("490.000"), balance("ACC-1001"));
  }

  @Test
  void reconciliationDetectsCorruptionAndLedgerIsImmutable() throws Exception {
    var cmd = command(UUID.randomUUID(), "10");
    service.transfer(cmd, "alice", UUID.randomUUID().toString());
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class,
          () ->
              s.executeUpdate(
                  "UPDATE ledger_entries SET signed_amount=-11 WHERE entry_type='DEBIT'"));
      s.executeUpdate(
          "UPDATE accounts SET available_balance=available_balance+1 WHERE id='ACC-1001'");
    }
    assertEquals(false, reconciliation.report().get("balanced"));
    assertEquals(List.of("ACC-1001"), reconciliation.report().get("accountDiscrepancies"));
  }

  @Test
  @TestSecurity(user = "bob")
  @JwtSecurity(claims = {@Claim(key = "scope", value = "payments:read accounts:read")})
  void unrelatedPrincipalCannotDiscoverReceipt() {
    given().get("/api/v1/payments/" + UUID.randomUUID()).then().statusCode(404);
    given()
        .contentType("application/json")
        .body(json(UUID.randomUUID(), "1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
  }

  @Test
  void bodySizeIsBounded() throws Exception {
    given()
        .contentType("application/json")
        .body(" ".repeat(65537))
        .post("/api/v1/payments")
        .then()
        .statusCode(413);
    assertEquals(0, count("payments"));
  }

  @Test
  void exhaustedLockWaitIsTransientAndCanBeRetried() throws Exception {
    var cmd = command(UUID.randomUUID(), "1");
    try (var c = db.getConnection();
        var statement = c.createStatement()) {
      c.setAutoCommit(false);
      statement.executeQuery("SELECT id FROM accounts WHERE id='ACC-1001' FOR UPDATE").close();
      var error =
          assertThrows(
              PaymentException.class,
              () -> service.transfer(cmd, "alice", UUID.randomUUID().toString()));
      assertEquals(503, error.http);
      assertEquals("TEMPORARILY_UNAVAILABLE", error.code);
      c.rollback();
    }
    assertEquals(0, count("payments"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(
        "COMPLETED", service.transfer(cmd, "alice", UUID.randomUUID().toString()).status());
  }

  @Test
  void reconciliationDetectsMissingLedgerFixture() throws Exception {
    var cmd = command(UUID.randomUUID(), "1");
    service.transfer(cmd, "alice", UUID.randomUUID().toString());
    try (var c = db.getConnection();
        var statement = c.createStatement()) {
      statement.execute("ALTER TABLE ledger_entries DISABLE TRIGGER immutable_ledger");
      try {
        statement.executeUpdate("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");
      } finally {
        statement.execute("ALTER TABLE ledger_entries ENABLE TRIGGER immutable_ledger");
      }
    }
    assertEquals(false, reconciliation.report().get("balanced"));
    assertEquals(
        List.of(cmd.paymentId().toString()), reconciliation.report().get("paymentDiscrepancies"));
  }

  @Test
  void observabilityAndPublishedContractAreAvailable() {
    given().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    given().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    given()
        .accept("application/json")
        .get("/openapi")
        .then()
        .statusCode(200)
        .body("info.title", equalTo("Payment Processing Backend"));
    given().get("/q/metrics").then().statusCode(200);
    given().get("/api/v1/admin/reconciliation").then().statusCode(403);
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"admin"})
  void authorizedAdministratorCanReconcile() {
    given()
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(200)
        .body("balanced", equalTo(true));
  }

  @Test
  void fieldErrorsIncludeTheDateWindow() {
    String body =
        json(UUID.randomUUID(), "0")
            .replaceFirst("[0-9]{4}-[0-9]{2}-[0-9]{2}T[^\"]+", "2020-01-01T00:00:00Z");
    given()
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("issues.field", hasItems("amount", "date"));
  }
}
