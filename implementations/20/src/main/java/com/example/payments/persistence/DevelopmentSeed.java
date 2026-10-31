package com.example.payments.persistence;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Explicit opt-in development data. Never resets an existing financial balance. */
@ApplicationScoped
public class DevelopmentSeed {
  @Inject AgroalDataSource db;

  @ConfigProperty(name = "payments.seed-demo", defaultValue = "false")
  boolean enabled;

  void start(@Observes StartupEvent event) {
    if (!enabled) return;
    try (var stream = getClass().getResourceAsStream("/db/dev/demo-seed.sql");
        Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      c.commit();
    } catch (Exception e) {
      throw new IllegalStateException("Development account seed failed", e);
    }
  }
}
