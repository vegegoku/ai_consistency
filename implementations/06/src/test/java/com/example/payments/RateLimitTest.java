package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RateLimitTest.Policy.class)
class RateLimitTest {
  @Inject DataSource db;

  public static class Policy implements QuarkusTestProfile {
    public Map<String, String> getConfigOverrides() {
      return Map.of("payment.requests-per-minute", "1");
    }
  }

  @Test
  void rateLimitIsSharedAndReturnsRetryAfter() throws Exception {
    try (var c = db.getConnection();
        var s = c.createStatement()) {
      s.execute("TRUNCATE rate_limits");
    }
    String token =
        new JwtAuthenticationTest()
            .token(
                "https://issuer.test",
                "payment-api",
                Instant.now().getEpochSecond() + 300,
                "accounts:read");
    given().auth().oauth2(token).get("/api/v1/accounts/ACC-1001/balance").then().statusCode(200);
    given()
        .auth()
        .oauth2(token)
        .get("/api/v1/accounts/ACC-1001/balance")
        .then()
        .statusCode(429)
        .header("Retry-After", equalTo("60"))
        .body("code", equalTo("RATE_LIMITED"));
  }
}
