package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class PaymentApiTest {
  @Inject DataSource data;

  @BeforeAll
  static void logging() {
    io.restassured.RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
  }

  @BeforeEach
  void seed() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE"
              + " principal_rate_limits,ledger_entries,payments,account_debit_authorizations,accounts,customers"
              + " CASCADE");
      s.execute("INSERT INTO customers(id,display_name) VALUES ('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES"
              + " ('A','alice',500,500),('B','bob',100,100)");
    }
  }

  String token(String subject, String scopes) {
    return Jwt.issuer("https://test.example")
        .audience("payment-service")
        .subject(subject)
        .claim("scope", scopes)
        .expiresAt(Instant.now().plusSeconds(300))
        .sign("privateKey.pem");
  }

  String auth() {
    return token("alice", "payments:write payments:read balances:read");
  }

  String json(String id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"invoice\"}";
  }

  @Test
  void jsonSuccessLookupAndBalance() {
    String id = UUID.randomUUID().toString(), body = json(id);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .header("Location", endsWith("/api/v1/payments/" + id))
        .header("X-Correlation-ID", notNullValue())
        .body("amount", equalTo("125.750"));
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("balance", equalTo("374.250"));
  }

  @Test
  void xmlAndJsonAreCanonicalEquivalent() {
    String id = UUID.randomUUID().toString(), date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml; charset=UTF-8")
        .accept("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("response.amount", equalTo("125.750"));
    String json = json(id).replaceFirst("\"date\":\"[^\"]+", "\"date\":\"" + date);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(json)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
  }

  @Test
  void authAndScopeRequired() {
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(token("alice", "payments:read"))
        .contentType("application/json")
        .body(json(UUID.randomUUID().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    given().auth().oauth2("garbage").get("/api/v1/accounts/A/balance").then().statusCode(401);
  }

  @Test
  void invalidIssuerAudienceAndExpiredJwtRejected() {
    for (String jwt :
        List.of(
            Jwt.issuer("https://evil.example")
                .audience("payment-service")
                .subject("alice")
                .expiresAt(Instant.now().plusSeconds(300))
                .sign("privateKey.pem"),
            Jwt.issuer("https://test.example")
                .audience("wrong")
                .subject("alice")
                .expiresAt(Instant.now().plusSeconds(300))
                .sign("privateKey.pem"),
            Jwt.issuer("https://test.example")
                .audience("payment-service")
                .subject("alice")
                .issuedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(3500))
                .sign("privateKey.pem")))
      given().auth().oauth2(jwt).get("/api/v1/accounts/A/balance").then().statusCode(401);
  }

  @Test
  void nonEnumerableLookupsAndDebitAuthorization() {
    given()
        .auth()
        .oauth2(token("bob", "payments:write balances:read payments:read"))
        .contentType("application/json")
        .body(json(UUID.randomUUID().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    given()
        .auth()
        .oauth2(token("bob", "balances:read"))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(404);
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/payments/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void mediaErrorsAndStandardValidation() {
    given()
        .auth()
        .oauth2(auth())
        .contentType("text/plain")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(415)
        .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .accept("text/plain")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"), "issues.size()", greaterThanOrEqualTo(6));
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body("{")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
  }

  @Test
  void xmlSecurityAndBodyLimit() {
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml; charset=UTF-8")
        .body(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(" ".repeat(65537))
        .post("/api/v1/payments")
        .then()
        .statusCode(413)
        .body("code", equalTo("REQUEST_TOO_LARGE"));
  }

  @Test
  void healthAndAdminReconciliation() {
    given().get("/q/health/live").then().statusCode(200);
    given().get("/q/health/ready").then().statusCode(200);
    given().auth().oauth2(auth()).get("/api/v1/admin/reconciliation").then().statusCode(403);
    given()
        .auth()
        .oauth2(token("operator", "payments:admin"))
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(200)
        .body("consistent", equalTo(true));
  }

  @Test
  void correlationIsUniquePerRequest() {
    String one =
        given()
            .auth()
            .oauth2(auth())
            .get("/api/v1/accounts/A/balance")
            .then()
            .statusCode(200)
            .extract()
            .header("X-Correlation-ID");
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .header("X-Correlation-ID", not(equalTo(one)));
  }

  @Test
  void rateLimitIsSharedAndReturnsRetryAfter() throws Exception {
    try (var c = data.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "INSERT INTO principal_rate_limits VALUES('alice',floor(extract(epoch FROM"
              + " clock_timestamp())/60),6000)");
    }
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(429)
        .header("Retry-After", "60")
        .body("code", equalTo("RATE_LIMITED"));
  }

  @Test
  void xmlValidationErrorsHaveSameContract() {
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml; charset=UTF-8")
        .accept("application/xml")
        .body("<payment/>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("response.code", equalTo("VALIDATION_FAILED"));
  }

  @Test
  void openApiIncludesExamplesAndErrors() {
    given()
        .get("/q/openapi?format=json")
        .then()
        .statusCode(200)
        .body(containsString("IDEMPOTENCY_CONFLICT"))
        .body(containsString("application/xml"));
  }

  @Test
  void conflictErrorPreservesValidPaymentIdentity() {
    String id = UUID.randomUUID().toString(), original = json(id);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(original)
        .post("/api/v1/payments")
        .then()
        .statusCode(201);
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(original.replace("125.750", "126.750"))
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("code", equalTo("IDEMPOTENCY_CONFLICT"), "paymentId", equalTo(id));
  }

  @Test
  void extremeFutureDateIsValidationFailure() {
    String body =
        json(UUID.randomUUID().toString())
            .replaceFirst("\"date\":\"[^\"]+", "\"date\":\"+999999999-01-01T00:00:00Z");
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }

  @io.quarkus.test.common.http.TestHTTPResource("/api/v1/payments")
  java.net.URI submission;

  @Test
  void chunkedOversizedBodyUsesStandardError() throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(submission)
            .header("Authorization", "Bearer " + auth())
            .header("Content-Type", "application/json")
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(" ".repeat(65537).getBytes())))
            .build();
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    org.junit.jupiter.api.Assertions.assertEquals(413, response.statusCode());
    org.junit.jupiter.api.Assertions.assertTrue(
        response.body().contains("REQUEST_TOO_LARGE"), response.body());
  }
}
