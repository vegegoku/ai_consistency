package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

@QuarkusTest
@QuarkusTestResource(ApiTest.Database.class)
public class ApiTest {
  public static class Database implements QuarkusTestResourceLifecycleManager {
    PostgreSQLContainer<?> db;
    static KeyPair keys;
    Path publicKey;

    public Map<String, String> start() {
      try {
        db = new PostgreSQLContainer<>("postgres:16-alpine");
        db.start();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        publicKey = Files.createTempFile("payment-test-key-", ".pem");
        Files.writeString(
            publicKey,
            "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        return Map.of(
            "quarkus.datasource.jdbc.url",
            db.getJdbcUrl(),
            "quarkus.datasource.username",
            db.getUsername(),
            "quarkus.datasource.password",
            db.getPassword(),
            "mp.jwt.verify.publickey.location",
            publicKey.toUri().toString(),
            "mp.jwt.verify.issuer",
            "https://issuer.test",
            "mp.jwt.verify.audiences",
            "payments-api",
            "quarkus.http.insecure-requests",
            "enabled",
            "payments.rate-per-minute",
            "100");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    public void stop() {
      if (db != null) db.stop();
      try {
        Files.deleteIfExists(publicKey);
      } catch (Exception ignored) {
      }
    }
  }

  @Inject DataSource ds;

  @BeforeEach
  void seed() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_buckets"
              + " CASCADE");
      s.execute("INSERT INTO customers(id,display_name)VALUES('alice','Alice'),('bob','Bob')");
      s.execute(
          "INSERT INTO"
              + " accounts(id,customer_id,currency,available_balance,opening_balance,status)VALUES('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
    }
  }

  String token(String subject, String scope) {
    return token(subject, scope, "payments-api", Instant.now().plusSeconds(300).getEpochSecond());
  }

  String token(String subject, String scope, String audience, long expires) {
    try {
      var enc = Base64.getUrlEncoder().withoutPadding();
      var mapper = new ObjectMapper();
      String a =
          enc.encodeToString(
              "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String b =
          enc.encodeToString(
              mapper.writeValueAsBytes(
                  Map.of(
                      "sub",
                      subject,
                      "scope",
                      scope,
                      "iss",
                      "https://issuer.test",
                      "aud",
                      audience,
                      "iat",
                      Instant.now().getEpochSecond(),
                      "exp",
                      expires)));
      String signed = a + "." + b;
      var sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(Database.keys.getPrivate());
      sig.update(signed.getBytes(StandardCharsets.UTF_8));
      return signed + "." + enc.encodeToString(sig.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String body(UUID id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"Invoice\"}";
  }

  @Test
  void successReplayBalanceAndRetrieval() {
    String jwt = token("alice", "payments:write payments:read");
    UUID id = UUID.randomUUID();
    String b = body(id);
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body(b)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("amount", equalTo("125.750"))
        .header("Location", endsWith(id.toString()))
        .header("X-Correlation-ID", notNullValue());
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body(b)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(jwt)
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
    given()
        .auth()
        .oauth2(jwt)
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("374.250"));
    given()
        .auth()
        .oauth2(token("bob", "payments:read"))
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(404);
  }

  @Test
  void xmlSecurityAndNegotiation() {
    String jwt = token("alice", "payments:write");
    String xml =
        "<payment><paymentId>"
            + UUID.randomUUID()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>1.000</amount><date>"
            + Instant.now()
            + "</date><purpose>Invoice</purpose></payment>";
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/xml")
        .accept("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .contentType("application/xml")
        .body(containsString("<amount>1.000</amount>"));
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/xml")
        .body(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'http://127.0.0.1:12345/'>]><payment>&x;</payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    given()
        .auth()
        .oauth2(jwt)
        .contentType("text/plain")
        .body("abc")
        .post("/api/v1/payments")
        .then()
        .statusCode(415);
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .accept("text/plain")
        .body(body(UUID.randomUUID()))
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
  }

  @Test
  void authenticationClaimsAndAuthorization() {
    given()
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2("invalid.token.value")
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401);
    given()
        .auth()
        .oauth2(
            token(
                "alice",
                "payments:read",
                "wrong-audience",
                Instant.now().plusSeconds(300).getEpochSecond()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401);
    given()
        .auth()
        .oauth2(
            token(
                "alice",
                "payments:read",
                "payments-api",
                Instant.now().minusSeconds(600).getEpochSecond()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401);
    given()
        .auth()
        .oauth2(token("alice", "payments:read"))
        .contentType("application/json")
        .body(body(UUID.randomUUID()))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    given()
        .auth()
        .oauth2(token("bob", "payments:write"))
        .contentType("application/json")
        .body(body(UUID.randomUUID()))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
  }

  @Test
  void malformedAggregatedValidationAndSize() {
    String jwt = token("alice", "payments:write");
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body("{")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("issues.size()", greaterThanOrEqualTo(6));
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body(" ".repeat(65537))
        .post("/api/v1/payments")
        .then()
        .statusCode(413);
  }

  @Test
  void healthAndAdminReconciliation() {
    given().get("/q/health/ready").then().statusCode(200);
    given()
        .auth()
        .oauth2(token("alice", "payments:read"))
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(403);
    given()
        .auth()
        .oauth2(token("operator", "payments:admin"))
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(200)
        .body("consistent", equalTo(true));
  }

  @Test
  void httpRateLimitHasStableErrorAndRetryAfter() throws Exception {
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      statement.execute(
          "INSERT INTO"
              + " rate_buckets(principal,window_start,hits)VALUES('alice',date_trunc('minute',now()),100)");
    }
    given()
        .auth()
        .oauth2(token("alice", "payments:read"))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(429)
        .body("code", equalTo("RATE_LIMITED"))
        .header("Retry-After", "60");
  }

  @Test
  void durableHttpRejectionAndConflict() {
    String jwt = token("alice", "payments:read payments:write");
    UUID id = UUID.randomUUID();
    String rejected = body(id).replace("125.750", "501.000");
    String first =
        given()
            .auth()
            .oauth2(jwt)
            .contentType("application/json")
            .body(rejected)
            .post("/api/v1/payments")
            .then()
            .statusCode(409)
            .body("code", equalTo("INSUFFICIENT_FUNDS"))
            .extract()
            .path("timestamp");
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body(rejected)
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("timestamp", equalTo(first));
    given()
        .auth()
        .oauth2(jwt)
        .contentType("application/json")
        .body(rejected.replace("501.000", "1.000"))
        .post("/api/v1/payments")
        .then()
        .statusCode(409)
        .body("code", equalTo("IDEMPOTENCY_CONFLICT"));
    given()
        .auth()
        .oauth2(jwt)
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("REJECTED"))
        .body("code", equalTo("INSUFFICIENT_FUNDS"));
    given()
        .auth()
        .oauth2(jwt)
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("500.000"));
  }
}
