package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
  private final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  public Map<String, String> start() {
    db.start();
    return Map.of(
        "quarkus.datasource.jdbc.url",
        db.getJdbcUrl(),
        "quarkus.datasource.username",
        db.getUsername(),
        "quarkus.datasource.password",
        db.getPassword());
  }

  public void stop() {
    db.stop();
  }
}
