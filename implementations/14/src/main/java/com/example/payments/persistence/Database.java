package com.example.payments.persistence;

import com.example.payments.Config;
import com.zaxxer.hikari.*;
import java.sql.*;
import org.flywaydb.core.Flyway;

public final class Database implements AutoCloseable {
  public final HikariDataSource pool;

  public Database(Config config, boolean migrate) {
    HikariConfig h = new HikariConfig();
    h.setJdbcUrl(config.jdbcUrl());
    h.setUsername(config.dbUser());
    h.setPassword(config.dbPassword());
    h.setMaximumPoolSize(16);
    h.setMinimumIdle(1);
    h.setConnectionTimeout(3000);
    h.setValidationTimeout(1000);
    h.addDataSourceProperty("socketTimeout", "10");
    h.addDataSourceProperty("connectTimeout", "3");
    h.setConnectionInitSql("SET TIME ZONE 'UTC'");
    pool = new HikariDataSource(h);
    if (migrate)
      Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate();
  }

  public boolean ready() {
    try (var c = pool.getConnection();
        var s = c.createStatement()) {
      s.setQueryTimeout(2);
      s.execute("SELECT 1");
      return true;
    } catch (SQLException e) {
      return false;
    }
  }

  @Override
  public void close() {
    pool.close();
  }
}
