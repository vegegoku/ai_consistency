package com.example.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@Provider
public class AuditFilter implements ContainerResponseFilter {
  @Inject RequestContext context;
  @Inject MeterRegistry metrics;
  private static final Logger LOG = Logger.getLogger(AuditFilter.class);

  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle("X-Correlation-ID", context.correlationId);
    try {
      metrics
          .counter("payments.requests", "http", Integer.toString(response.getStatus()))
          .increment();
      MDC.put("correlationId", context.correlationId);
      if (context.paymentId != null) MDC.put("paymentId", context.paymentId);
      MDC.put("httpStatus", response.getStatus());
      MDC.put("status", context.status);
      MDC.put("code", context.code);
      MDC.put("latencyMs", (System.nanoTime() - context.started) / 1_000_000);
      LOG.info("API request completed");
    } catch (RuntimeException ignored) {
    } finally {
      MDC.clear();
    }
  }
}
