package com.example.payments.observability;

import com.example.payments.api.Responses;
import com.example.payments.domain.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@Provider
@Priority(1100)
public class RequestGuard implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;
  @Inject Responses responses;
  @Inject MeterRegistry metrics;

  @ConfigProperty(name = "payments.require-https", defaultValue = "true")
  boolean requireHttps;

  @ConfigProperty(name = "payments.requests-per-minute", defaultValue = "600")
  int rate;

  private static final Logger LOG = Logger.getLogger(RequestGuard.class);

  record Window(long minute, int count) {}

  final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public void filter(ContainerRequestContext ctx) {
    String correlation = UUID.randomUUID().toString();
    ctx.setProperty("correlation", correlation);
    ctx.setProperty("started", System.nanoTime());
    String path = ctx.getUriInfo().getPath().replaceFirst("^/+", "");
    if (!path.startsWith("api/")) return;
    try {
      responses.media(ctx.getHeaderString("Accept"));
      if (requireHttps && !ctx.getSecurityContext().isSecure())
        throw new ApiFailure(400, "TLS_REQUIRED");
      if (identity.isAnonymous()) throw new ApiFailure(401, "UNAUTHENTICATED");
      Object scopeClaim = jwt.getClaim("scope");
      String scope = scopeClaim instanceof String s ? s : null;
      String wanted = ctx.getMethod().equals("POST") ? "payments:write" : "payments:read";
      boolean admin = path.contains("reconciliation");
      if (admin) wanted = "payments:admin";
      if (scope == null || !Arrays.asList(scope.split(" ")).contains(wanted))
        throw new ApiFailure(403, "FORBIDDEN");
      long minute = System.currentTimeMillis() / 60000;
      Window w =
          windows.compute(
              jwt.getSubject(),
              (k, old) ->
                  new Window(minute, old == null || old.minute != minute ? 1 : old.count + 1));
      if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
      if (w.count > rate) throw new ApiFailure(429, "RATE_LIMITED");
    } catch (ApiFailure e) {
      String accept = e.http == 406 ? "application/json" : ctx.getHeaderString("Accept");
      var response = responses.send(e.http, responses.error(e, correlation), accept, correlation);
      if (e.http == 401)
        response =
            jakarta.ws.rs.core.Response.fromResponse(response)
                .header("WWW-Authenticate", "Bearer")
                .build();
      if (e.http == 429)
        response =
            jakarta.ws.rs.core.Response.fromResponse(response).header("Retry-After", "60").build();
      ctx.abortWith(response);
    }
  }

  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    String correlation =
        Objects.toString(request.getProperty("correlation"), UUID.randomUUID().toString());
    response.getHeaders().putSingle("X-Correlation-ID", correlation);
    metrics
        .counter(
            "payments.requests",
            "method",
            request.getMethod(),
            "status",
            Integer.toString(response.getStatus()))
        .increment();
    if (response.getStatus() == 500) metrics.counter("payments.unexpected.failures").increment();
    long start = request.getProperty("started") instanceof Long l ? l : System.nanoTime();
    LOG.infov(
        "correlationId={0} status={1} latencyMs={2}",
        correlation, response.getStatus(), (System.nanoTime() - start) / 1000000);
  }
}
