package com.example.payments.security;

import static com.example.payments.persistence.Sql.*;

import com.example.payments.application.Limits;
import com.example.payments.domain.ApiException;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Instant;

@ApplicationScoped
public class RateLimiter {
  @Inject AgroalDataSource dataSource;
  @Inject Limits limits;

  public void check(Caller caller) {
    try (var c = dataSource.getConnection();
        var s =
            statement(
                c,
                "INSERT INTO rate_windows(principal,window_start,requests) VALUES(?,?,1) ON"
                    + " CONFLICT(principal,window_start) DO UPDATE SET"
                    + " requests=LEAST(rate_windows.requests+1,2147483647) RETURNING requests",
                caller.subject(),
                Instant.now().getEpochSecond() / 60);
        var rs = s.executeQuery()) {
      rs.next();
      if (rs.getInt(1) > limits.rate)
        throw new ApiException(429, "RATE_LIMITED", "Request rate exceeded.");
    } catch (SQLException e) {
      org.jboss.logging.Logger.getLogger(RateLimiter.class)
          .warnv("Admission database failure SQLState={0}", e.getSQLState());
      throw new ApiException(
          503, "TEMPORARILY_UNAVAILABLE", "Request admission is temporarily unavailable.");
    }
  }
}
