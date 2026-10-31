package com.example.payments;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
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
      s.execute(
          "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers,rate_windows"
              + " RESTART IDENTITY CASCADE");
      s.execute(
          "INSERT INTO customers(id,display_name,status)"
              + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      s.execute(
          "INSERT INTO"
              + " accounts(id,customer_id,opening_balance,available_balance,status,public_receive)"
              + " VALUES('A','alice',500,500,'ACTIVE',true),('B','bob',100,100,'ACTIVE',true)");
    }
  }

  static String token(String sub, String scopes, long expires) throws Exception {
    return token(sub, scopes, expires, "local-payments", "payment-service");
  }

  static String token(String sub, String scopes, long expires, String issuer, String audience)
      throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
    String payload =
        encoder.encodeToString(
            ("{\"iss\":\""
                    + issuer
                    + "\",\"aud\":\""
                    + audience
                    + "\",\"sub\":\""
                    + sub
                    + "\",\"iat\":"
                    + (Instant.now().getEpochSecond() - 1)
                    + ",\"exp\":"
                    + expires
                    + ",\"scope\":\""
                    + scopes
                    + "\"}")
                .getBytes());
    String key =
        Files.readString(Path.of("scripts/local-private.pem"))
            .replaceAll("-----[^-]+-----", "")
            .replaceAll("\\s", "");
    var privateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)));
    var signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey);
    signer.update((header + "." + payload).getBytes());
    return header + "." + payload + "." + encoder.encodeToString(signer.sign());
  }

  String auth() throws Exception {
    return token("alice", "payments:read payments:write", Instant.now().getEpochSecond() + 300);
  }

  String json(UUID id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now().toString()
        + "\",\"purpose\":\"invoice\"}";
  }

  @Test
  void jsonSuccessAndQueryAreCanonical() throws Exception {
    UUID id = UUID.randomUUID();
    String body = json(id);
    var result =
        given()
            .auth()
            .oauth2(auth())
            .contentType("application/json")
            .body(body)
            .post("/api/v1/payments");
    result
        .then()
        .statusCode(201)
        .header("Location", endsWith(id.toString()))
        .header("X-Correlation-ID", notNullValue())
        .body("amount", equalTo("125.750"))
        .body("status", equalTo("COMPLETED"));
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(200)
        .body("processedAt", equalTo(result.path("processedAt")));
    given().auth().oauth2(auth()).get("/api/v1/payments/" + id).then().statusCode(200);
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("balance", equalTo("374.250"));
  }

  @Test
  void xmlSuccessAndResponse() throws Exception {
    String body =
        "<payment><paymentId>"
            + UUID.randomUUID()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + Instant.now()
            + "</date><purpose>invoice</purpose></payment>";
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml")
        .accept("application/xml")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .body("response.amount", equalTo("125.750"));
  }

  @Test
  void malformedXmlAndUnknownPropertiesAreRejected() throws Exception {
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml")
        .body(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'http://127.0.0.1:9'>]><payment><notes>&x;</notes></payment>")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("MALFORMED_MESSAGE"));
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(json(UUID.randomUUID()).replace("\"purpose\"", "\"unknown\""))
        .post("/api/v1/payments")
        .then()
        .statusCode(400);
  }

  @Test
  void mediaNegotiationAndValidation() throws Exception {
    given()
        .auth()
        .oauth2(auth())
        .contentType("text/plain")
        .body("hello")
        .post("/api/v1/payments")
        .then()
        .statusCode(415)
        .body("code", equalTo("UNSUPPORTED_MEDIA_TYPE"));
    given()
        .auth()
        .oauth2(auth())
        .accept("image/png")
        .get("/api/v1/accounts/A/balance")
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
        .body("issues.size()", greaterThanOrEqualTo(6));
  }

  @Test
  void authenticationScopeAndOwnership() throws Exception {
    given()
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(token("alice", "payments:read", Instant.now().getEpochSecond() - 120))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
    given()
        .auth()
        .oauth2(token("alice", "", Instant.now().getEpochSecond() + 300))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(403);
    given()
        .auth()
        .oauth2(token("bob", "payments:read", Instant.now().getEpochSecond() + 300))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(404);
  }

  @Test
  void correlationIdsAreUnique() throws Exception {
    String one =
        given().auth().oauth2(auth()).get("/api/v1/accounts/A/balance").header("X-Correlation-ID");
    String two =
        given().auth().oauth2(auth()).get("/api/v1/accounts/A/balance").header("X-Correlation-ID");
    assertNotEquals(one, two);
  }

  @Test
  void healthAndAdminAccess() throws Exception {
    get("/q/health/ready").then().statusCode(200);
    given().auth().oauth2(auth()).get("/api/v1/admin/reconciliation").then().statusCode(403);
    given()
        .auth()
        .oauth2(token("alice", "payments:admin", Instant.now().getEpochSecond() + 300))
        .get("/api/v1/admin/reconciliation")
        .then()
        .statusCode(200)
        .body("issues.size()", equalTo(0));
  }

  @Test
  void issuerAudienceAndSignatureAreVerified() throws Exception {
    long expiry = Instant.now().getEpochSecond() + 300;
    for (String value :
        List.of(
            token("alice", "payments:read", expiry, "wrong-issuer", "payment-service"),
            token("alice", "payments:read", expiry, "local-payments", "wrong-audience")))
      given()
          .auth()
          .oauth2(value)
          .get("/api/v1/accounts/A/balance")
          .then()
          .statusCode(401)
          .body("code", equalTo("UNAUTHENTICATED"));
    String value = auth();
    int index = value.lastIndexOf('.') + 3;
    String invalid =
        value.substring(0, index)
            + (value.charAt(index) == 'A' ? 'B' : 'A')
            + value.substring(index + 1);
    given()
        .auth()
        .oauth2(invalid)
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }

  @Test
  void crossFormatReplayHasOneFinancialEffect() throws Exception {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    String body = json(id);
    date = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("date").asText();
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(body)
        .post("/api/v1/payments")
        .then()
        .statusCode(201);
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/xml")
        .body(xml)
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(auth())
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(200)
        .body("balance", equalTo("374.250"));
  }

  @Test
  void bodyLimitIsEnforced() throws Exception {
    given()
        .auth()
        .oauth2(auth())
        .contentType("application/json")
        .body(" ".repeat(65537))
        .post("/api/v1/payments")
        .then()
        .statusCode(413)
        .body("code", equalTo("MALFORMED_MESSAGE"));
  }

  @Test
  void chunkedBodyLimitUsesErrorContract() throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + io.restassured.RestAssured.port + "/api/v1/payments"))
            .header("Authorization", "Bearer " + auth())
            .header("Content-Type", "application/json")
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(new byte[65537])))
            .build();
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(413, response.statusCode());
    assertEquals(
        "MALFORMED_MESSAGE",
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.body())
            .path("code")
            .asText());
  }
}
