package com.example.payments;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.payments.application.*;
import com.example.payments.observability.TransferTelemetry;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class PaymentApiTest {
  @io.quarkus.test.common.http.TestHTTPResource java.net.URI baseUri;
  @Inject AgroalDataSource ds;
  @Inject ReconciliationService reconciliation;
  @Inject Limits limits;
  @InjectMock TransferTelemetry telemetry;
  String id;
  String date;

  String token(String subject, String scopes) {
    long now = Instant.now().getEpochSecond();
    return Jwt.issuer("https://issuer.test")
        .subject(subject)
        .audience("payments-api")
        .claim("scope", scopes)
        .issuedAt(now)
        .expiresAt(now + 600)
        .sign();
  }

  String alice() {
    return token("alice", "payments:write payments:read accounts:read");
  }

  @BeforeEach
  void resetDatabase() throws Exception {
    reset(telemetry);
    id = UUID.randomUUID().toString();
    date = Instant.now().toString();
    limits.rate = 10000;
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_windows"
              + " RESTART IDENTITY CASCADE");
      s.execute(
          new String(
              getClass().getResourceAsStream("/test-seed.sql").readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  String json(String paymentId, String amount, String from, String to) {
    return "{\"paymentId\":\""
        + paymentId
        + "\",\"fromAccount\":\""
        + from
        + "\",\"toAccount\":\""
        + to
        + "\",\"amount\":\""
        + amount
        + "\",\"date\":\""
        + date
        + "\",\"purpose\":\"Invoice\",\"notes\":\" services \"}";
  }

  String xml(String paymentId, String amount) {
    return "<payment><paymentId>"
        + paymentId
        + "</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>"
        + amount
        + "</amount><date>"
        + date
        + "</date><purpose>Invoice</purpose><notes>services</notes></payment>";
  }

  Response post(String body, String type) {
    return given().auth().oauth2(alice()).contentType(type).body(body).post("/api/v1/payments");
  }

  BigDecimal balance(String id) throws SQLException {
    try (var c = ds.getConnection();
        var s = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      s.setString(1, id);
      try (var rs = s.executeQuery()) {
        rs.next();
        return rs.getBigDecimal(1);
      }
    }
  }

  long count(String table) throws SQLException {
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var rs = s.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void jsonSuccessCreatesExactConservedBalancesAndLinkedLedger() throws Exception {
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(201)
        .header("Location", org.hamcrest.Matchers.endsWith(id))
        .header("X-Correlation-ID", notNullValue())
        .body("amount", equalTo("125.750"), "status", equalTo("COMPLETED"));
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
    assertEquals(new BigDecimal("225.750"), balance("ACC-2002"));
    assertEquals(1, count("payments"));
    assertEquals(2, count("ledger_entries"));
    assertTrue(reconciliation.reconcile().consistent());
    given()
        .auth()
        .oauth2(alice())
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("amount", equalTo("125.750"));
    given()
        .auth()
        .oauth2(alice())
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("374.250"));
  }

  @Test
  void xmlSuccessAndJsonReplayReturnOriginalReceipt() throws Exception {
    var first =
        post(xml(id, "125.75"), "application/xml")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("processedAt");
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(200)
        .body("processedAt", equalTo(first));
    given()
        .auth()
        .oauth2(alice())
        .accept("application/xml")
        .contentType("application/xml")
        .body(xml(id, "125.750"))
        .post("/api/v1/payments")
        .then()
        .statusCode(200)
        .body("response.amount", equalTo("125.750"));
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void sameIdDifferentInstructionConflicts() throws Exception {
    post(json(id, "1", "ACC-1001", "ACC-2002"), "application/json").then().statusCode(201);
    post(json(id, "2", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void invalidDocumentsAndFieldsNeverMoveMoney() throws Exception {
    for (String body :
        List.of(
            "{",
            json(id, "1.0001", "ACC-1001", "ACC-2002"),
            json(id, "0", "ACC-1001", "ACC-2002"),
            json(id, "1", "ACC-1001", "ACC-1001")))
      post(body, "application/json").then().statusCode(400);
    post(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'http://127.0.0.1:1/secret'>]><payment><purpose>&x;</purpose></payment>",
            "application/xml")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(0, count("payments"));
  }

  @Test
  void insufficientFundsAndFrozenAccountsAreDurableRejections() throws Exception {
    post(json(id, "501", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("status", equalTo("REJECTED"), "code", equalTo("INSUFFICIENT_FUNDS"));
    post(json(id, "501", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("code", equalTo("INSUFFICIENT_FUNDS"));
    given()
        .auth()
        .oauth2(alice())
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("REJECTED"));
    post(json(UUID.randomUUID().toString(), "1", "ACC-FROZEN", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("code", equalTo("ACCOUNT_NOT_ACTIVE"));
    post(json(UUID.randomUUID().toString(), "1", "ACC-1001", "ACC-CLOSED"), "application/json")
        .then()
        .statusCode(409);
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void authorizationScopesAndNonEnumerationAreEnforced() throws Exception {
    given()
        .contentType("application/json")
        .body(json(id, "1", "ACC-1001", "ACC-2002"))
        .post("/api/v1/payments")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(token("mallory", "payments:write"))
        .contentType("application/json")
        .body(json(id, "1", "ACC-1001", "ACC-2002"))
        .post("/api/v1/payments")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    given()
        .auth()
        .oauth2(token("mallory", "accounts:read"))
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(404);
    given()
        .auth()
        .oauth2(token("mallory", "payments:read"))
        .get("/api/v1/payments/" + UUID.randomUUID())
        .then()
        .statusCode(404);
    given()
        .auth()
        .oauth2(token("alice", "accounts:read"))
        .contentType("application/json")
        .body(json(UUID.randomUUID().toString(), "1", "ACC-1001", "ACC-2002"))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
  }

  @Test
  void invalidIssuerAudienceExpirationAndSignatureAreRejected() {
    long now = Instant.now().getEpochSecond();
    assertEquals(
        "0",
        org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("mp.jwt.verify.clock.skew", String.class));
    for (String bad :
        List.of(
            Jwt.issuer("https://evil.test")
                .subject("alice")
                .audience("payments-api")
                .expiresAt(now + 600)
                .sign(),
            Jwt.issuer("https://issuer.test")
                .subject("alice")
                .audience("wrong")
                .expiresAt(now + 600)
                .sign(),
            Jwt.issuer("https://issuer.test")
                .subject("alice")
                .audience("payments-api")
                .issuedAt(now - 100)
                .expiresAt(now - 10)
                .sign(),
            alice().substring(0, alice().length() - 6) + "broken"))
      given()
          .auth()
          .oauth2(bad)
          .get("/api/v1/accounts/ACC-1001/balance")
          .then()
          .log()
          .ifValidationFails()
          .statusCode(401);
  }

  @Test
  void mediaNegotiationAndRateLimit() {
    post("x", "text/plain").then().statusCode(415).body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    given()
        .auth()
        .oauth2(alice())
        .contentType("application/json")
        .accept("text/csv")
        .body(json(id, "1", "ACC-1001", "ACC-2002"))
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    limits.rate = 1;
    given()
        .auth()
        .oauth2(token("rate-user", "accounts:read"))
        .get("/api/v1/accounts/none/balance")
        .then()
        .statusCode(404);
    given()
        .auth()
        .oauth2(token("rate-user", "accounts:read"))
        .get("/api/v1/accounts/none/balance")
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue());
  }

  @Test
  void concurrentDuplicateIdMovesMoneyExactlyOnce() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            () ->
                post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json").statusCode());
      var statuses = new ArrayList<Integer>();
      for (var future : pool.invokeAll(tasks)) statuses.add(future.get());
      assertEquals(1, statuses.stream().filter(s -> s == 201).count());
      assertTrue(statuses.stream().allMatch(s -> s == 201 || s == 200), statuses.toString());
    }
    assertEquals(new BigDecimal("374.250"), balance("ACC-1001"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentDistinctIdsCannotOverdrawSource() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            () ->
                post(
                        json(UUID.randomUUID().toString(), "100", "ACC-1001", "ACC-2002"),
                        "application/json")
                    .statusCode());
      var statuses = new ArrayList<Integer>();
      for (var future : pool.invokeAll(tasks)) statuses.add(future.get());
      assertEquals(5, statuses.stream().filter(s -> s == 201).count());
      assertEquals(7, statuses.stream().filter(s -> s == 409).count());
    }
    assertEquals(new BigDecimal("0.000"), balance("ACC-1001"));
    assertEquals(new BigDecimal("600.000"), balance("ACC-2002"));
    assertTrue(reconciliation.reconcile().consistent());
  }

  @Test
  void failureAfterDebitRollsBackAllFinancialState() throws Exception {
    doThrow(new IllegalStateException("injected failure")).when(telemetry).afterDebit();
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(500)
        .body("code", equalTo("INTERNAL_ERROR"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(new BigDecimal("100.000"), balance("ACC-2002"));
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
    reset(telemetry);
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json").then().statusCode(201);
  }

  @Test
  void lostResponseRetryAndFreshConnectionsDiscoverCommittedOutcome() throws Exception {
    // Deliberately discard the original HTTP response, as a timed-out client would.
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json");
    post(json(id, "125.750", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
    try (var c =
            DriverManager.getConnection(
                ds.getConfiguration()
                    .connectionPoolConfiguration()
                    .connectionFactoryConfiguration()
                    .jdbcUrl(),
                "test",
                "test");
        var s = c.createStatement();
        var rs = s.executeQuery("SELECT count(*) FROM payments WHERE status='COMPLETED'")) {
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void reconciliationDetectsCorruptFixtureAndImmutableLedgerRejectsUpdates() throws Exception {
    post(json(id, "1", "ACC-1001", "ACC-2002"), "application/json").then().statusCode(201);
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class, () -> s.execute("UPDATE ledger_entries SET signed_amount=0"));
      assertThrows(SQLException.class, () -> s.execute("UPDATE payments SET purpose='changed'"));
      s.execute("UPDATE accounts SET available_balance=available_balance+1 WHERE id='ACC-1001'");
    }
    assertFalse(reconciliation.reconcile().consistent());
    assertTrue(reconciliation.reconcile().accountIds().contains("ACC-1001"));
    given()
        .auth()
        .oauth2(token("operator", "operations:read"))
        .get("/api/v1/operations/reconciliation")
        .then()
        .statusCode(200)
        .body("consistent", equalTo(false));
    given().auth().oauth2(alice()).get("/api/v1/operations/reconciliation").then().statusCode(403);
  }

  @Test
  void lockTimeoutIsTransientAndCanRetryWithSameIdentity() throws Exception {
    limits.lockMs = 100;
    limits.retries = 1;
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT * FROM accounts WHERE id='ACC-1001' FOR UPDATE");
      post(json(id, "1", "ACC-1001", "ACC-2002"), "application/json")
          .then()
          .statusCode(503)
          .body("code", equalTo("TEMPORARILY_UNAVAILABLE"));
      c.rollback();
    } finally {
      limits.lockMs = 2000;
      limits.retries = 2;
    }
    assertEquals(0, count("payments"));
    post(json(id, "1", "ACC-1001", "ACC-2002"), "application/json").then().statusCode(201);
  }

  @Test
  void requestBoundsNegotiatedErrorsAndCorrelationAreConsistent() {
    given()
        .accept("application/xml")
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(401)
        .contentType("application/xml")
        .body("response.code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(alice())
        .contentType("application/json")
        .body("x".repeat(70000))
        .post("/api/v1/payments")
        .then()
        .statusCode(413)
        .contentType("application/json")
        .body("code", equalTo("PAYLOAD_TOO_LARGE"))
        .header("X-Correlation-ID", notNullValue());
    String untrusted = UUID.randomUUID().toString();
    var first =
        given()
            .auth()
            .oauth2(alice())
            .header("X-Correlation-ID", untrusted)
            .get("/api/v1/accounts/ACC-1001/balance")
            .then()
            .statusCode(200)
            .extract()
            .header("X-Correlation-ID");
    var second =
        given()
            .auth()
            .oauth2(alice())
            .get("/api/v1/accounts/ACC-1001/balance")
            .then()
            .statusCode(200)
            .extract()
            .header("X-Correlation-ID");
    assertNotEquals(untrusted, first);
    assertNotEquals(first, second);
  }

  @Test
  void explicitDelegatedGrantsAllowDebitsAndUnrelatedCallersCannotViewExistingPayments()
      throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "INSERT INTO account_permissions(account_id,principal,can_debit)"
              + " VALUES('ACC-1001','delegate',true)");
    }
    given()
        .auth()
        .oauth2(token("delegate", "payments:write"))
        .contentType("application/json")
        .body(json(id, "1", "ACC-1001", "ACC-2002"))
        .post("/api/v1/payments")
        .then()
        .statusCode(201);
    given()
        .auth()
        .oauth2(token("mallory", "payments:read"))
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(404);
    given()
        .auth()
        .oauth2(token("bob", "payments:read"))
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200);
  }

  @Test
  void chunkedOversizedBodyHasSafeErrorEnvelope() throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(baseUri.resolve("/api/v1/payments"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + alice())
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream("x".repeat(70000).getBytes())))
            .build();
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(413, response.statusCode(), response.body());
    assertTrue(response.body().contains("PAYLOAD_TOO_LARGE"), response.body());
    assertTrue(response.headers().firstValue("X-Correlation-ID").isPresent());
  }

  @Test
  void nonexistentAccountsAndAccountPoliciesRejectWithoutFinancialEffects() throws Exception {
    post(json(id, "1", "ACC-1001", "MISSING"), "application/json")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    post(json(UUID.randomUUID().toString(), "1", "MISSING", "ACC-2002"), "application/json")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET receive_enabled=false WHERE id='ACC-2002'");
    }
    post(json(UUID.randomUUID().toString(), "1", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("code", equalTo("ACCOUNT_NOT_ACTIVE"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("UPDATE accounts SET receive_enabled=true WHERE id='ACC-2002'");
      s.execute("UPDATE accounts SET max_transfer=0.500 WHERE id='ACC-1001'");
    }
    post(json(UUID.randomUUID().toString(), "1", "ACC-1001", "ACC-2002"), "application/json")
        .then()
        .statusCode(409)
        .body("code", equalTo("PAYMENT_LIMIT_EXCEEDED"));
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
  }

  @Test
  void opposingConcurrentTransfersUseConsistentLockOrdering() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 20; i++) {
        boolean forward = i % 2 == 0;
        tasks.add(
            () ->
                given()
                    .auth()
                    .oauth2(token(forward ? "alice" : "bob", "payments:write"))
                    .contentType("application/json")
                    .body(
                        json(
                            UUID.randomUUID().toString(),
                            "1",
                            forward ? "ACC-1001" : "ACC-2002",
                            forward ? "ACC-2002" : "ACC-1001"))
                    .post("/api/v1/payments")
                    .statusCode());
      }
      for (var result : pool.invokeAll(tasks)) assertEquals(201, result.get());
    }
    assertEquals(new BigDecimal("500.000"), balance("ACC-1001"));
    assertEquals(new BigDecimal("100.000"), balance("ACC-2002"));
    assertTrue(reconciliation.reconcile().consistent());
  }

  @Test
  void reconciliationDoesNotChangeSubsequentTransferTransactionMode() throws Exception {
    for (int i = 0; i < 12; i++) {
      assertTrue(reconciliation.reconcile().consistent());
      post(json(UUID.randomUUID().toString(), "1", "ACC-1001", "ACC-2002"), "application/json")
          .then()
          .statusCode(201);
    }
    assertEquals(new BigDecimal("488.000"), balance("ACC-1001"));
    assertTrue(reconciliation.reconcile().consistent());
  }
}
