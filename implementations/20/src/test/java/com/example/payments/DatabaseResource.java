package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class DatabaseResource implements QuarkusTestResourceLifecycleManager {
  private PostgreSQLContainer<?> postgres;

  public Map<String, String> start() {
    postgres =
        new PostgreSQLContainer<>("postgres:14")
            .withDatabaseName("payments20")
            .withUsername("payments")
            .withPassword("test-only");
    postgres.start();
    return Map.of(
        "quarkus.datasource.jdbc.url",
        postgres.getJdbcUrl(),
        "quarkus.datasource.username",
        postgres.getUsername(),
        "quarkus.datasource.password",
        postgres.getPassword(),
        "quarkus.http.auth.proactive",
        "false");
  }

  public void stop() {
    if (postgres != null) postgres.stop();
  }
}
