package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
  private PostgreSQLContainer<?> postgres;

  public Map<String, String> start() {
    System.setProperty("api.version", "1.44");
    postgres = new PostgreSQLContainer<>("postgres:14");
    postgres.start();
    return Map.of(
        "quarkus.datasource.jdbc.url",
        postgres.getJdbcUrl(),
        "quarkus.datasource.username",
        postgres.getUsername(),
        "quarkus.datasource.password",
        postgres.getPassword(),
        "mp.jwt.verify.publickey.location",
        "publicKey.pem",
        "mp.jwt.verify.issuer",
        "https://test.local",
        "mp.jwt.verify.audiences",
        "payments",
        "payments.failure-injection",
        "true",
        "quarkus.otel.sdk.disabled",
        "true");
  }

  public void stop() {
    if (postgres != null) postgres.stop();
  }
}
