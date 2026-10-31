package com.example.payments.api;

import io.micrometer.core.instrument.Metrics;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

@Provider
public class RequestAudit implements ContainerResponseFilter {
  private static final Logger LOG = Logger.getLogger(RequestAudit.class);

  public void filter(ContainerRequestContext req, ContainerResponseContext res) {
    Object start = req.getProperty("started");
    if (start == null) return;
    long nanos = System.nanoTime() - (long) start;
    String correlation =
        Objects.toString(req.getProperty("correlation"), UUID.randomUUID().toString());
    res.getHeaders().putSingle("X-Correlation-ID", correlation);
    try {
      Metrics.counter("payment_http_requests", "status", String.valueOf(res.getStatus()))
          .increment();
      Metrics.timer("payment_http_latency").record(nanos, TimeUnit.NANOSECONDS);
      LOG.infof(
          "correlationId=%s paymentId=%s status=%d code=%s paymentStatus=%s latencyMs=%d",
          correlation,
          Objects.toString(req.getProperty("paymentId"), "none"),
          res.getStatus(),
          Objects.toString(req.getProperty("errorCode"), "NONE"),
          Objects.toString(req.getProperty("paymentStatus"), "ERROR"),
          nanos / 1000000);
    } catch (Exception ignored) {
    }
  }
}
