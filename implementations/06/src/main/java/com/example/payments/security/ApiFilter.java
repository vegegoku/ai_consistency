package com.example.payments.security;

import com.example.payments.domain.PaymentException;
import com.example.payments.observability.RequestContext;
import com.example.payments.persistence.Sql;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.io.*;
import java.util.*;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Provider
@SecuredApi
@Priority(Priorities.AUTHORIZATION)
public class ApiFilter implements ContainerRequestFilter {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;
  @Inject RequestContext context;
  @Inject DataSource db;

  @ConfigProperty(name = "payment.body-limit", defaultValue = "65536")
  int bodyLimit;

  @ConfigProperty(name = "payment.requests-per-minute", defaultValue = "600")
  int rate;

  @ConfigProperty(name = "payment.require-https", defaultValue = "true")
  boolean https;

  @Override
  public void filter(ContainerRequestContext request) throws IOException {
    String incoming = request.getHeaderString("X-Correlation-ID");
    if (incoming != null && incoming.matches("[0-9a-fA-F-]{36}"))
      try {
        context.correlationId = UUID.fromString(incoming).toString();
      } catch (Exception ignored) {
      }
    String path = request.getUriInfo().getPath().replaceFirst("^/", "");
    if (https && !request.getSecurityContext().isSecure())
      throw new PaymentException(400, "HTTPS_REQUIRED");
    if (identity.isAnonymous()) throw new PaymentException(401, "UNAUTHENTICATED");
    String required =
        request.getMethod().equals("POST")
            ? "payments:submit"
            : path.contains("/accounts/") ? "accounts:read" : "payments:read";
    Object claim = jwt.getClaim("scope");
    Set<String> scopes =
        new HashSet<>(Arrays.asList(claim == null ? new String[0] : claim.toString().split(" ")));
    if (!scopes.contains(required) && !identity.hasRole("admin"))
      throw new PaymentException(403, "FORBIDDEN");
    try (var c = db.getConnection();
        var p =
            Sql.statement(
                c,
                "INSERT INTO rate_limits(principal,window_start,requests)"
                    + " VALUES(?,date_trunc('minute',now()),1) ON CONFLICT(principal,window_start)"
                    + " DO UPDATE SET requests=rate_limits.requests+1 RETURNING requests",
                identity.getPrincipal().getName());
        var r = p.executeQuery()) {
      r.next();
      if (r.getInt(1) > rate) throw new PaymentException(429, "RATE_LIMITED");
    } catch (java.sql.SQLException e) {
      throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
    }
    if (request.getMethod().equals("POST")) {
      byte[] body = request.getEntityStream().readNBytes(bodyLimit + 1);
      if (body.length > bodyLimit) throw new PaymentException(413, "PAYLOAD_TOO_LARGE");
      request.setEntityStream(new ByteArrayInputStream(body));
    }
  }
}
