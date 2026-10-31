package com.example.payments.persistence;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;

/**
 * Explicit runtime-only development fixtures, tracked separately from production schema history.
 */
@ApplicationScoped
public class DevelopmentSeed {
  @Inject DataSource dataSource;

  @ConfigProperty(name = "payment.seed.enabled", defaultValue = "false")
  boolean enabled;

  void initialize(@Observes StartupEvent event) {
    if (enabled)
      Flyway.configure()
          .dataSource(dataSource)
          .locations("db/dev")
          .table("flyway_dev_history")
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .cleanDisabled(true)
          .load()
          .migrate();
  }
}
