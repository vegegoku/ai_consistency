package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class DatabaseResource implements QuarkusTestResourceLifecycleManager {
  private final IdentityServer identity = new IdentityServer();
  private final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  public Map<String, String> start() {
    db.start();
    var configuration =
        new java.util.HashMap<String, String>(
            Map.of(
                "quarkus.datasource.jdbc.url",
                db.getJdbcUrl(),
                "quarkus.datasource.username",
                db.getUsername(),
                "quarkus.datasource.password",
                db.getPassword(),
                "quarkus.oidc.enabled",
                "false",
                "payment.require-tls",
                "false",
                "payment.rate-per-minute",
                "100000",
                "quarkus.otel.sdk.disabled",
                "true"));
    configuration.putAll(identity.start());
    return configuration;
  }

  public void stop() {
    identity.stop();
    db.stop();
  }
}
