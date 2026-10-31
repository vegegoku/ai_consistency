package com.example.payments.persistence;

import java.sql.*;
import java.time.Instant;

public final class Sql {
  private Sql() {}

  public static PreparedStatement statement(Connection c, String sql, Object... args)
      throws SQLException {
    var p = c.prepareStatement(sql);
    p.setQueryTimeout(5);
    for (int i = 0; i < args.length; i++) {
      Object v = args[i];
      if (v instanceof Instant t) v = Timestamp.from(t);
      p.setObject(i + 1, v);
    }
    return p;
  }

  public static int update(Connection c, String sql, Object... args) throws SQLException {
    try (var p = statement(c, sql, args)) {
      return p.executeUpdate();
    }
  }
}
