package com.example.payments.security;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiSecurity implements ContainerRequestFilter {
  @Inject JWTParser parser;
  @Inject PaymentService service;

  @ConfigProperty(name = "payments.rate-per-minute", defaultValue = "6000")
  int rate;

  @Override
  public void filter(ContainerRequestContext request) {
    String path = request.getUriInfo().getPath();
    if (!(path.startsWith("api/v1/") || path.startsWith("/api/v1/"))) return;
    String raw = request.getHeaderString("X-Correlation-ID");
    UUID correlation;
    try {
      correlation = UUID.fromString(raw);
      if (!correlation.toString().equals(raw)) throw new IllegalArgumentException();
    } catch (Exception e) {
      correlation = UUID.randomUUID();
    }
    request.setProperty("correlation", correlation.toString());
    request.setProperty("started", System.nanoTime());
    String authorization = request.getHeaderString("Authorization");
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ApiException(401, "UNAUTHENTICATED");
    org.eclipse.microprofile.jwt.JsonWebToken jwt;
    try {
      jwt = parser.parse(authorization.substring(7));
      if (jwt.getSubject() == null
          || jwt.getSubject().isBlank()
          || jwt.getSubject().length() > 100
          || jwt.getExpirationTime() <= java.time.Instant.now().getEpochSecond())
        throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new ApiException(401, "UNAUTHENTICATED");
    }
    Object scopeClaim = jwt.getClaim("scope");
    String scope = scopeClaim instanceof String str ? str : null;
    Set<String> scopes =
        new HashSet<>(Arrays.asList(scope == null ? new String[] {} : scope.split(" ")));
    boolean admin = scopes.contains("payments:admin");
    String required = request.getMethod().equals("POST") ? "payments:write" : "payments:read";
    if (!admin && !scopes.contains(required)) throw new ApiException(403, "FORBIDDEN");
    if (!service.rateAllowed(jwt.getSubject(), rate)) throw new ApiException(429, "RATE_LIMITED");
    request.setProperty("caller", new Caller(jwt.getSubject(), admin));
  }
}
