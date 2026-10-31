package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.*;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
  private PostgreSQLContainer<?> pg;

  public Map<String, String> start() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
    return Map.of(
        "quarkus.datasource.jdbc.url",
        pg.getJdbcUrl(),
        "quarkus.datasource.username",
        pg.getUsername(),
        "quarkus.datasource.password",
        pg.getPassword(),
        "mp.jwt.verify.publickey.location",
        "local-public.pem");
  }

  public void stop() {
    if (pg != null) pg.stop();
  }
}
