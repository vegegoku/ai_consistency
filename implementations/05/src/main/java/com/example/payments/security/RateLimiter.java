package com.example.payments.security;

import com.example.payments.domain.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared PostgreSQL buckets give the same bound across stateless replicas. */
@ApplicationScoped
public class RateLimiter {
  @Inject DataSource db;

  @ConfigProperty(name = "payments.rate-limit")
  int limit;

  public void check(String principal) {
    try (var c = db.getConnection();
        var s =
            c.prepareStatement(
                "INSERT INTO request_limits(principal,window_start,requests) VALUES"
                    + " (?,date_trunc('minute',clock_timestamp()),1) ON CONFLICT(principal) DO"
                    + " UPDATE SET window_start=EXCLUDED.window_start,requests=CASE WHEN"
                    + " request_limits.window_start=EXCLUDED.window_start THEN"
                    + " request_limits.requests+1 ELSE 1 END RETURNING requests")) {
      s.setQueryTimeout(3);
      s.setString(1, principal);
      try (var r = s.executeQuery()) {
        r.next();
        if (r.getLong(1) > limit) throw new ApiException(429, "RATE_LIMITED");
      }
    } catch (SQLException e) {
      throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
