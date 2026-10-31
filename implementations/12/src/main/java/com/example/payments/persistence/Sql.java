package com.example.payments.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class Sql {
  private Sql() {}

  public static PreparedStatement statement(Connection c, String text, Object... args)
      throws SQLException {
    var s = c.prepareStatement(text);
    for (int i = 0; i < args.length; i++) {
      Object value = args[i];
      if (value instanceof Instant instant) value = Timestamp.from(instant);
      s.setObject(i + 1, value);
    }
    return s;
  }

  public static int update(Connection c, String text, Object... args) throws SQLException {
    try (var s = statement(c, text, args)) {
      return s.executeUpdate();
    }
  }

  public static String string(Connection c, String text, Object... args) throws SQLException {
    try (var s = statement(c, text, args);
        var rs = s.executeQuery()) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  public static boolean exists(Connection c, String text, Object... args) throws SQLException {
    try (var s = statement(c, text, args);
        var rs = s.executeQuery()) {
      return rs.next();
    }
  }
}
