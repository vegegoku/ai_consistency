package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(DatabaseResource.class)
class JwtSecurityTest {
  String token(String issuer, String audience, int expiry, String scope, boolean invalidSignature)
      throws Exception {
    JwtClaims claims = new JwtClaims();
    claims.setSubject("alice");
    claims.setIssuer(issuer);
    claims.setAudience(audience);
    claims.setIssuedAtToNow();
    claims.setExpirationTimeMinutesInTheFuture(expiry);
    claims.setClaim("scope", scope);
    claims.setClaim("customer_id", "alice");
    claims.setStringListClaim("groups", "customer");
    String pem =
        new String(
                getClass().getResourceAsStream("/test-private.pem").readAllBytes(),
                StandardCharsets.UTF_8)
            .replaceAll("-----[^-]+-----", "")
            .replaceAll("\\s", "");
    PrivateKey key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    if (invalidSignature) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      key = generator.generateKeyPair().getPrivate();
    }
    JsonWebSignature jws = new JsonWebSignature();
    jws.setPayload(claims.toJson());
    jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
    jws.setKey(key);
    return jws.getCompactSerialization();
  }

  @Test
  void noTokenReturnsStandard401() {
    given()
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(401)
        .body("code", equalTo("UNAUTHENTICATED"));
  }

  @Test
  void validSignatureReachesValidation() throws Exception {
    given()
        .auth()
        .oauth2(token("https://issuer.example.test", "payments-api", 5, "payments:write", false))
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }

  @Test
  void invalidIssuerAudienceExpiryAndSignatureDenied() throws Exception {
    for (String value :
        List.of(
            token("wrong", "payments-api", 5, "payments:write", false),
            token("https://issuer.example.test", "wrong", 5, "payments:write", false),
            token("https://issuer.example.test", "payments-api", -5, "payments:write", false),
            token("https://issuer.example.test", "payments-api", 5, "payments:write", true))) {
      given()
          .auth()
          .oauth2(value)
          .contentType("application/json")
          .body("{}")
          .post("/api/v1/payments")
          .then()
          .statusCode(401)
          .body("code", equalTo("UNAUTHENTICATED"));
    }
  }

  @Test
  void missingScopeForbidden() throws Exception {
    given()
        .auth()
        .oauth2(token("https://issuer.example.test", "payments-api", 5, "accounts:read", false))
        .contentType("application/json")
        .body("{}")
        .post("/api/v1/payments")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
  }
}
