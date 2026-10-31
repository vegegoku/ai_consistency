package com.example.payments.api;

import com.example.payments.application.Limits;
import com.example.payments.domain.ApiException;
import com.example.payments.observability.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.*;

@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestFilters implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject RequestContext context;
  @Inject Limits limits;
  @Inject MeterRegistry metrics;

  @ConfigProperty(name = "payment.trust-correlation-header", defaultValue = "false")
  boolean trustCorrelation;

  @Override
  public void filter(ContainerRequestContext request) {
    if (trustCorrelation) {
      try {
        context.correlationId(UUID.fromString(request.getHeaderString("X-Correlation-ID")));
      } catch (Exception ignored) {
      }
    }
    MDC.put("correlationId", context.correlationId().toString());
    if (request.getUriInfo().getPath().startsWith("api/")
        && limits.requireTls
        && !request.getSecurityContext().isSecure())
      throw new ApiException(400, "TLS_REQUIRED", "HTTPS is required.");
  }

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    MDC.put("correlationId", context.correlationId().toString());
    response.getHeaders().putSingle("X-Correlation-ID", context.correlationId().toString());
    response.getHeaders().putSingle("Cache-Control", "no-store");
    response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
    metrics
        .counter(
            "payments.http.requests",
            "method",
            request.getMethod(),
            "status",
            Integer.toString(response.getStatus()))
        .increment();
    Logger.getLogger(RequestFilters.class)
        .infov(
            "status={0} latencyMs={1}",
            response.getStatus(), (System.nanoTime() - context.started()) / 1000000);
    MDC.remove("correlationId");
    MDC.remove("paymentId");
  }
}
