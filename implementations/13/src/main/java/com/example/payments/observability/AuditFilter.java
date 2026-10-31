package com.example.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@Provider
public class AuditFilter implements ContainerResponseFilter {
  private static final Logger LOG = Logger.getLogger(AuditFilter.class);
  @Inject RequestContext context;
  @Inject MeterRegistry metrics;

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle("X-Correlation-ID", context.correlationId());
    response.getHeaders().putSingle("Cache-Control", "no-store");
    response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
    // Telemetry must never affect the outcome of an already committed transfer.
    try {
      metrics
          .counter(
              "payments.http.requests",
              "status",
              String.valueOf(response.getStatus()),
              "code",
              context.code())
          .increment();
      MDC.put("correlationId", context.correlationId());
      MDC.put("paymentId", context.paymentId());
      MDC.put("status", response.getStatus());
      MDC.put("code", context.code());
      MDC.put("latencyMicros", (System.nanoTime() - context.start()) / 1000);
      LOG.info("Payment API request completed");
    } catch (RuntimeException ignored) {
    } finally {
      for (String key :
          new String[] {"correlationId", "paymentId", "status", "code", "latencyMicros"})
        MDC.remove(key);
    }
  }
}
