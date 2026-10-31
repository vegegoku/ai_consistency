package com.example.payments.security;

import com.example.payments.api.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestSecurity implements ContainerRequestFilter {
  @Inject JWTParser parser;
  @Inject RequestContext context;
  @Inject ApiResponses responses;
  @Inject DataSource data;

  @ConfigProperty(name = "payments.rate-limit-per-minute", defaultValue = "6000")
  int limit;

  @ConfigProperty(name = "payments.trust-correlation-header", defaultValue = "false")
  boolean trustCorrelation;

  public void filter(ContainerRequestContext request) {
    if (!request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/v1/")) return;
    try {
      String header = request.getHeaderString("X-Correlation-ID");
      if (trustCorrelation && header != null && header.matches("[0-9a-fA-F-]{36}"))
        context.correlationId(UUID.fromString(header).toString());
      String authorization = request.getHeaderString("Authorization");
      if (authorization == null
          || !authorization.startsWith("Bearer ")
          || authorization.length() > 16384) throw unauthenticated();
      org.eclipse.microprofile.jwt.JsonWebToken jwt;
      try {
        jwt = parser.parse(authorization.substring(7));
      } catch (Exception invalid) {
        throw unauthenticated();
      }
      String principal = jwt.getSubject();
      if (principal == null
          || principal.isBlank()
          || principal.length() > 128
          || jwt.getExpirationTime() <= System.currentTimeMillis() / 1000) throw unauthenticated();
      Object scope = jwt.getClaim("scope");
      if (!(scope instanceof String)) throw ApiFailure.business(403, "FORBIDDEN");
      Set<String> scopes = new HashSet<>(Arrays.asList(((String) scope).split(" +")));
      String path = request.getUriInfo().getPath().replaceFirst("^/", "");
      String required =
          path.startsWith("api/v1/admin/")
              ? "payments:admin"
              : path.startsWith("api/v1/accounts/")
                  ? "balances:read"
                  : request.getMethod().equals("POST") ? "payments:write" : "payments:read";
      if (!scopes.contains(required)) throw ApiFailure.business(403, "FORBIDDEN");
      context.principal(principal);
      rateLimit(principal);
    } catch (ApiFailure e) {
      request.abortWith(responses.error(e));
    } catch (Exception e) {
      request.abortWith(
          responses.error(
              new ApiFailure(500, "INTERNAL_ERROR", "The request could not be processed.")));
    }
  }

  private void rateLimit(String principal) {
    try (var c = data.getConnection();
        var s =
            c.prepareStatement(
                "INSERT INTO principal_rate_limits(principal,minute_bucket,request_count)"
                    + " VALUES(?,floor(extract(epoch FROM clock_timestamp())/60),1) ON"
                    + " CONFLICT(principal) DO UPDATE SET minute_bucket=excluded.minute_bucket,"
                    + " request_count=CASE WHEN"
                    + " principal_rate_limits.minute_bucket=excluded.minute_bucket THEN"
                    + " principal_rate_limits.request_count+1 ELSE 1 END RETURNING"
                    + " request_count")) {
      s.setQueryTimeout(3);
      s.setString(1, principal);
      try (var r = s.executeQuery()) {
        r.next();
        if (r.getInt(1) > limit)
          throw new ApiFailure(
              429, "RATE_LIMITED", "Request limit exceeded. Retry after the current minute.");
      }
    } catch (SQLException e) {
      throw new ApiFailure(
          503, "TEMPORARILY_UNAVAILABLE", "Processing is temporarily unavailable.");
    }
  }

  private ApiFailure unauthenticated() {
    return new ApiFailure(401, "UNAUTHENTICATED", "A valid bearer token is required.");
  }
}
