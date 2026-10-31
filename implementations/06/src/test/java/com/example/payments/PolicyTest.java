package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PolicyTest.Policy.class)
class PolicyTest {
  public static class Policy implements QuarkusTestProfile {
    public Map<String, String> getConfigOverrides() {
      return Map.of("payment.requests-per-minute", "1", "payment.require-https", "true");
    }
  }

  @Test
  void productionPolicyRejectsCleartextEvenWithValidAuthentication() throws Exception {
    String token =
        new JwtAuthenticationTest()
            .token(
                "https://issuer.test",
                "payment-api",
                Instant.now().getEpochSecond() + 300,
                "accounts:read");
    given()
        .auth()
        .oauth2(token)
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(400)
        .body("code", equalTo("HTTPS_REQUIRED"));
  }
}
