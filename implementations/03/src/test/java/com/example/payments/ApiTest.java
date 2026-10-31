package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class ApiTest {
  @Inject DataSource ds;

  @BeforeEach
  void seed() throws Exception {
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

  String token(String subject, String scope, long expires) throws Exception {
    var enc = Base64.getUrlEncoder().withoutPadding();
    var mapper = new ObjectMapper();
    String h = enc.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
    String p =
        enc.encodeToString(
            mapper.writeValueAsBytes(
                Map.of(
                    "sub",
                    subject,
                    "iss",
                    "https://issuer.example.test",
                    "aud",
                    "payments",
                    "iat",
                    Instant.now().getEpochSecond(),
                    "exp",
                    expires,
                    "scope",
                    scope)));
    String data = h + "." + p;
    var sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(PostgresResource.keys.getPrivate());
    sig.update(data.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return data + "." + enc.encodeToString(sig.sign());
  }

  String token() throws Exception {
    return token(
        "alice",
        "payments:read payments:write payments:admin",
        Instant.now().plusSeconds(3600).getEpochSecond());
  }

  String body(String id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"invoice\"}";
  }

  @Test
  void completeApiFlow() throws Exception {
    String id = UUID.randomUUID().toString();
    String body = body(id);
    var auth = token();
    given()
        .auth()
        .oauth2(auth)
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .header("X-Correlation-ID", notNullValue())
        .body("amount", equalTo("125.750"));
    given()
        .auth()
        .oauth2(auth)
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(auth)
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
    given()
        .auth()
        .oauth2(auth)
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("availableBalance", equalTo("374.250"));
    given()
        .auth()
        .oauth2(auth)
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(200)
        .body("healthy", equalTo(true));
  }

  @Test
  void jsonAndXmlErrorsAndNegotiation() throws Exception {
    String auth = token();
    given()
        .auth()
        .oauth2(auth)
        .contentType("text/plain")
        .body("x")
        .post("/api/v1/payments")
        .then()
        .statusCode(415);
    given()
        .auth()
        .oauth2(auth)
        .accept("text/html")
        .contentType("application/json")
        .body(body(UUID.randomUUID().toString()))
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
    given()
        .auth()
        .oauth2(auth)
        .contentType("application/xml")
        .body(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'file:///etc/passwd'>]><payment><notes>&x;</notes></payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    String xml =
        "<payment><paymentId>"
            + UUID.randomUUID()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>1.000</amount><date>"
            + Instant.now()
            + "</date><purpose>invoice</purpose></payment>";
    given()
        .auth()
        .oauth2(auth)
        .contentType("application/xml")
        .accept("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("response.amount", equalTo("1.000"));
  }

  @Test
  void security() throws Exception {
    given().get("/api/v1/accounts/A/balance").then().statusCode(401);
    given()
        .auth()
        .oauth2(token("alice", "payments:read", Instant.now().minusSeconds(120).getEpochSecond()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401);
    given()
        .auth()
        .oauth2(token("alice", "other", Instant.now().plusSeconds(3600).getEpochSecond()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(403);
    given()
        .auth()
        .oauth2(token("mallory", "payments:read", Instant.now().plusSeconds(3600).getEpochSecond()))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(404);
  }

  @Test
  void rateLimitHasRetryAfter() throws Exception {
    String t =
        token("quota-test", "payments:read", Instant.now().plusSeconds(3600).getEpochSecond());
    for (int i = 0; i < 20; i++)
      given().auth().oauth2(t).get("/api/v1/accounts/A/balance").then().statusCode(404);
    given()
        .auth()
        .oauth2(t)
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(429)
        .header("Retry-After", "60")
        .body("code", equalTo("RATE_LIMITED"));
  }
}
