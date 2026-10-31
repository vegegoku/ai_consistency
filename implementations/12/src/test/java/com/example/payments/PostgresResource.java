package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
  private final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payments")
          .withUsername("test")
          .withPassword("test");

  public Map<String, String> start() {
    postgres.start();
    return Map.of(
        "quarkus.datasource.jdbc.url",
        postgres.getJdbcUrl(),
        "quarkus.datasource.username",
        postgres.getUsername(),
        "quarkus.datasource.password",
        postgres.getPassword());
  }

  public void stop() {
    postgres.stop();
  }
}
