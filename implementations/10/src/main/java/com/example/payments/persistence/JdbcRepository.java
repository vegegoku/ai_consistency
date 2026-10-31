package com.example.payments.persistence;

import java.sql.*;
import java.util.*;

/** Parameterized JDBC operations used within the application-owned transaction. */
public final class JdbcRepository {
  private JdbcRepository() {}

  public static Map<String, Object> row(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setQueryTimeout(5);
      bind(p, args);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return null;
        Map<String, Object> m = new HashMap<>();
        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
          m.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
        return m;
      }
    }
  }

  public static int update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setQueryTimeout(5);
      bind(p, args);
      return p.executeUpdate();
    }
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  public static void execute(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.setQueryTimeout(5);
      s.execute(sql);
    }
  }
}
