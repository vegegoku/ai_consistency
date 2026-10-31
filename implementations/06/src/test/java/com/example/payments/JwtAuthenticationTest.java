package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JwtAuthenticationTest {
  String token(String issuer, String audience, long expiration, String scope) throws Exception {
    String pem =
        new String(
            getClass().getResourceAsStream("/test-private.pem").readAllBytes(),
            StandardCharsets.UTF_8);
    byte[] der =
        Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    var b64 = Base64.getUrlEncoder().withoutPadding();
    long now = Instant.now().getEpochSecond();
    String header =
        b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        b64.encodeToString(
            ("{\"iss\":\""
                    + issuer
                    + "\",\"aud\":\""
                    + audience
                    + "\",\"sub\":\"alice\",\"upn\":\"alice\",\"iat\":"
                    + now
                    + ",\"exp\":"
                    + expiration
                    + ",\"scope\":\""
                    + scope
                    + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    String text = header + "." + payload;
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(key);
    signature.update(text.getBytes(StandardCharsets.UTF_8));
    return text + "." + b64.encodeToString(signature.sign());
  }

  @Test
  void signedTokenIsAcceptedAndScopeEnforced() throws Exception {
    given()
        .auth()
        .oauth2(
            token(
                "https://issuer.test",
                "payment-api",
                Instant.now().getEpochSecond() + 300,
                "accounts:read"))
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(200)
        .body("currency", equalTo("JOD"));
    given()
        .auth()
        .oauth2(
            token("https://issuer.test", "payment-api", Instant.now().getEpochSecond() + 300, ""))
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }

  @Test
  void invalidIssuerAudienceExpirationAndSignatureAreRejected() throws Exception {
    long future = Instant.now().getEpochSecond() + 300;
    for (String t :
        List.of(
            token("https://wrong.test", "payment-api", future, "accounts:read"),
            token("https://issuer.test", "wrong", future, "accounts:read"),
            token(
                "https://issuer.test",
                "payment-api",
                Instant.now().getEpochSecond() - 300,
                "accounts:read"),
            token("https://issuer.test", "payment-api", future, "accounts:read") + "bad")) {
      given()
          .auth()
          .oauth2(t)
          .get("/api/v1/accounts/ACC-1001/balance")
          .then()
          .statusCode(401)
          .body("code", equalTo("UNAUTHENTICATED"));
    }
  }

  @Test
  void missingTokenUsesStandardError() {
    given()
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }
}
