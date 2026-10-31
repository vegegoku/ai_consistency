package com.example.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject MeterRegistry metrics;

  public void filter(ContainerRequestContext request) {
    String raw = request.getHeaderString("X-Correlation-ID");
    String id = raw != null && raw.matches("[a-fA-F0-9-]{36}") ? raw : UUID.randomUUID().toString();
    request.setProperty("correlationId", id);
    request.setProperty("started", System.nanoTime());
    MDC.put("correlationId", id);
  }

  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle("X-Correlation-ID", request.getProperty("correlationId"));
    response.getHeaders().putSingle("Cache-Control", "no-store");
    response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
    try {
      metrics
          .counter("payments.http.requests", "status", Integer.toString(response.getStatus()))
          .increment();
      if (request.getProperty("started") instanceof Long start)
        metrics
            .timer("payments.http.latency")
            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
