package com.example.payments;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JwtSecurityTest {
  String token(String issuer, String audience, long expiration, String scope) throws Exception {
    String pem =
        new String(
                getClass().getResourceAsStream("/test-private.pem").readAllBytes(),
                StandardCharsets.UTF_8)
            .replaceAll("-----[^-]+-----", "")
            .replaceAll("\\s", "");
    PrivateKey key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    var b64 = Base64.getUrlEncoder().withoutPadding();
    String body =
        b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + b64.encodeToString(
                new ObjectMapper()
                    .writeValueAsBytes(
                        Map.of(
                            "iss",
                            issuer,
                            "aud",
                            audience,
                            "sub",
                            "alice",
                            "iat",
                            Instant.now().getEpochSecond() - 1,
                            "exp",
                            expiration,
                            "scope",
                            scope)));
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key);
    signer.update(body.getBytes(StandardCharsets.US_ASCII));
    return body + "." + b64.encodeToString(signer.sign());
  }

  @Test
  void validatesIssuerAudienceExpirationSignatureAndScope() throws Exception {
    long future = Instant.now().getEpochSecond() + 300;
    String good = token("https://test.payment.invalid", "payment-service", future, "payments:read");
    given()
        .auth()
        .oauth2(good)
        .get("/api/v1/payments/00000000-0000-0000-0000-000000000000")
        .then()
        .statusCode(404);
    for (String invalid :
        List.of(
            token("wrong", "payment-service", future, "payments:read"),
            token("https://test.payment.invalid", "wrong", future, "payments:read"),
            token(
                "https://test.payment.invalid",
                "payment-service",
                Instant.now().getEpochSecond() - 300,
                "payments:read"),
            good.substring(0, good.lastIndexOf('.') + 1) + "invalid")) {
      given()
          .auth()
          .oauth2(invalid)
          .get("/api/v1/accounts/A/balance")
          .then()
          .statusCode(401)
          .body("code", org.hamcrest.Matchers.equalTo("UNAUTHENTICATED"));
    }
    given()
        .auth()
        .oauth2(token("https://test.payment.invalid", "payment-service", future, "unrelated"))
        .get("/api/v1/accounts/A/balance")
        .then()
        .statusCode(403);
  }
}
