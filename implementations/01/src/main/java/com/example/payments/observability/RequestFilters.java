package com.example.payments.observability;

import com.example.payments.domain.PaymentException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestFilters implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject RequestContext context;
  @Inject MeterRegistry registry;

  @ConfigProperty(name = "payment.require-tls", defaultValue = "true")
  boolean requireTls;

  @ConfigProperty(name = "payment.trust-correlation-header", defaultValue = "false")
  boolean trustCorrelation;

  private static final Logger LOG = Logger.getLogger(RequestFilters.class);

  public void filter(ContainerRequestContext request) {
    if (trustCorrelation) {
      try {
        context.correlation(
            UUID.fromString(request.getHeaderString("X-Correlation-ID")).toString());
      } catch (Exception ignored) {
      }
    }
    if (requireTls
        && request.getUriInfo().getPath().startsWith("api/")
        && !request.getUriInfo().getRequestUri().getScheme().equalsIgnoreCase("https"))
      throw new PaymentException(403, "TLS_REQUIRED");
  }

  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle("X-Correlation-ID", context.correlation());
    response.getHeaders().putSingle("Cache-Control", "no-store");
    if (response.getStatus() == 429 || response.getStatus() == 503)
      response.getHeaders().putSingle("Retry-After", "2");
    try {
      double millis = (System.nanoTime() - context.started()) / 1000000.0;
      registry
          .counter(
              "payment.http.requests",
              "status",
              String.valueOf(response.getStatus()),
              "code",
              context.code())
          .increment();
      if (response.getStatus() == 500) registry.counter("payment.unexpected.failures").increment();
      registry
          .timer("payment.http.latency")
          .record(System.nanoTime() - context.started(), TimeUnit.NANOSECONDS);
      org.jboss.logging.MDC.put("correlationId", context.correlation());
      if (context.paymentId() != null) org.jboss.logging.MDC.put("paymentId", context.paymentId());
      org.jboss.logging.MDC.put("status", response.getStatus());
      org.jboss.logging.MDC.put("code", context.code());
      org.jboss.logging.MDC.put("latencyMs", millis);
      try {
        LOG.info("Payment request processed");
      } finally {
        for (String key : List.of("correlationId", "paymentId", "status", "code", "latencyMs"))
          org.jboss.logging.MDC.remove(key);
      }
    } catch (RuntimeException ignored) {
    }
  }
}
