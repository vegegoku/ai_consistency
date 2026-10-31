package com.example.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Provider
@Priority(1)
public class RequestAudit implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject MeterRegistry metrics;

  @ConfigProperty(name = "payments.trust-correlation-header", defaultValue = "false")
  boolean trusted;

  private static final Logger LOG = Logger.getLogger(RequestAudit.class);

  public void filter(ContainerRequestContext c) {
    UUID id = null;
    if (trusted) {
      try {
        id = UUID.fromString(c.getHeaderString("X-Correlation-ID"));
      } catch (Exception ignored) {
      }
    }
    c.setProperty("correlationId", id == null ? UUID.randomUUID() : id);
    c.setProperty("started", System.nanoTime());
  }

  public void filter(ContainerRequestContext c, ContainerResponseContext r) {
    Object id = c.getProperty("correlationId");
    r.getHeaders().putSingle("X-Correlation-ID", String.valueOf(id));
    r.getHeaders().putSingle("Cache-Control", "no-store");
    try {
      long elapsed = System.nanoTime() - (long) c.getProperty("started");
      metrics
          .counter("payments.http.requests", "status", Integer.toString(r.getStatus()))
          .increment();
      metrics.timer("payments.http.latency").record(elapsed, TimeUnit.NANOSECONDS);
      LOG.infof(
          "correlationId=%s paymentId=%s status=%s code=%s latencyMicros=%s",
          id,
          c.getProperty("paymentId"),
          r.getStatus(),
          c.getProperty("outcomeCode"),
          elapsed / 1000);
    } catch (RuntimeException ignored) {
    }
  }
}
