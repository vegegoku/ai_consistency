package com.example.payments;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(DatabaseResource.class)
class ApiTest {
  private static final String INSTRUCTION_DATE = OffsetDateTime.now(ZoneOffset.UTC).toString();
  @Inject DataSource ds;
  @io.quarkus.test.common.http.TestHTTPResource java.net.URI base;

  @BeforeEach
  void seed() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE"
              + " ledger_entries,payments,account_permissions,accounts,customers,rate_limit_buckets"
              + " RESTART IDENTITY CASCADE");
      s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status)"
              + " VALUES('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')");
    }
  }

  String json(String id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + INSTRUCTION_DATE
        + "\",\"purpose\":\"Invoice\"}";
  }

  String xml(String id) {
    return "<payment><paymentId>"
        + id
        + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
        + INSTRUCTION_DATE
        + "</date><purpose>Invoice</purpose></payment>";
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"payments:write", "payments:read"})
  void jsonXmlReplayAndQueries() {
    var id = UUID.randomUUID().toString();
    given()
        .contentType("application/json")
        .body(json(id))
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .header("Location", endsWith(id))
        .header("X-Correlation-ID", notNullValue())
        .body("amount", equalTo("125.750"));
    given()
        .contentType("application/xml")
        .accept("application/xml")
        .body(xml(id))
        .post("/api/v1/payments")
        .then()
        .statusCode(200)
        .body("receipt.amount", equalTo("125.750"));
    get("/api/v1/payments/" + id).then().statusCode(200).body("status", equalTo("COMPLETED"));
    get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("374.250"));
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"payments:write"})
  void mediaAndMalformed() {
    given()
        .contentType("application/json")
        .accept("application/json;q=0.000")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    given()
        .contentType("text/plain")
        .body("hi")
        .post("/api/v1/payments")
        .then()
        .statusCode(415)
        .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    given()
        .contentType("application/json")
        .accept("text/html")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    given()
        .contentType("application/json")
        .body("{")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    given()
        .contentType("application/xml")
        .body("<!DOCTYPE payment [<!ENTITY x SYSTEM 'http://127.0.0.1:1/'>]><payment>&x;</payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(
      user = "mallory",
      roles = {"payments:write", "payments:read"})
  void unauthorizedSourceAndHiddenBalance() {
    given()
        .contentType("application/json")
        .body(json(UUID.randomUUID().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    get("/api/v1/accounts/A/balance").then().statusCode(404);
    get("/api/v1/admin/reconciliation").then().statusCode(403);
  }

  @Test
  void unauthenticated() {
    get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"payments:read"})
  void correlationFreshPerRequest() {
    String first = get("/api/v1/accounts/A/balance").header("X-Correlation-ID");
    String second = get("/api/v1/accounts/A/balance").header("X-Correlation-ID");
    org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
  }

  @Test
  void validatesRealJwtSignatureIssuerAudienceExpiryAndScopes() {
    given()
        .auth()
        .oauth2(IdentityServer.token(Map.of()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200);
    for (var override :
        List.of(
            Map.<String, Object>of("iss", "https://invalid.example"),
            Map.<String, Object>of("aud", "wrong"),
            Map.<String, Object>of("exp", Instant.now().minusSeconds(100).getEpochSecond()))) {
      given()
          .auth()
          .oauth2(IdentityServer.token(override))
          .get("/api/v1/accounts/A/balance")
          .then()
          .statusCode(401)
          .body("code", equalTo("UNAUTHENTICATED"));
    }
    String token = IdentityServer.token(Map.of());
    given()
        .auth()
        .oauth2(token.substring(0, token.lastIndexOf('.') + 1) + "invalid")
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(IdentityServer.token(Map.of("scope", "unrelated")))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(403);
  }

  @Test
  void loadSample100RequestsPerSecond() throws Exception {
    var client =
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();
    String token = IdentityServer.token(Map.of());
    var samples = new ArrayList<java.util.concurrent.Future<long[]>>();
    long start = System.nanoTime();
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 1000; i++) {
        long deadline = start + i * 10_000_000L;
        long wait = deadline - System.nanoTime();
        if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        samples.add(
            workers.submit(
                () -> {
                  String payload = json(UUID.randomUUID().toString()).replace("125.750", "0.001");
                  var request =
                      java.net.http.HttpRequest.newBuilder(base.resolve("/api/v1/payments"))
                          .header("Authorization", "Bearer " + token)
                          .header("Content-Type", "application/json")
                          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                          .build();
                  long before = System.nanoTime();
                  var response =
                      client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                  return new long[] {response.statusCode(), System.nanoTime() - before};
                }));
      }
    }
    var latency = new ArrayList<Long>();
    for (var sample : samples) {
      long[] result = sample.get();
      org.junit.jupiter.api.Assertions.assertEquals(201, result[0]);
      latency.add(result[1]);
    }
    java.util.Collections.sort(latency);
    double p95 = latency.get(949) / 1000000.0;
    System.out.printf("LOAD_SAMPLE requests=1000 offeredRps=100 p95Ms=%.3f%n", p95);
    org.junit.jupiter.api.Assertions.assertTrue(p95 < 500, "p95 exceeded reference target: " + p95);
  }

  @Test
  void healthAndDocumentedContract() {
    get("/q/health/live").then().statusCode(200);
    get("/q/health/ready").then().statusCode(200);
    given()
        .accept("application/json")
        .get("/q/openapi")
        .then()
        .statusCode(200)
        .body("info.title", equalTo("Payment Processing API"));
  }

  @Test
  @TestSecurity(
      user = "alice",
      roles = {"payments:write", "payments:read"})
  void xmlFirstTransferUsesSameAccounting() {
    given()
        .contentType("application/xml")
        .body(xml(UUID.randomUUID().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("amount", equalTo("125.750"));
    get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("374.250"));
  }
}
