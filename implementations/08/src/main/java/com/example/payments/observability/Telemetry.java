package com.example.payments.observability;

import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

public final class Telemetry {
  private static final Logger LOG = Logger.getLogger(Telemetry.class);

  private Telemetry() {}

  public static void retry(String state) {
    try {
      Metrics.counter("payments.retries").increment();
      if ("55P03".equals(state) || "57014".equals(state))
        Metrics.counter("payments.lock.timeouts").increment();
    } catch (RuntimeException ignored) {
    }
  }

  public static void event(
      String correlation,
      String payment,
      String status,
      String code,
      long nanos,
      boolean replay,
      boolean submission) {
    try {
      Metrics.counter("payments.requests", "status", status, "code", code).increment();
      Metrics.timer("payments.processing").record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
      if (replay) Metrics.counter("payments.replays").increment();
      if (submission && !replay && status.equals("COMPLETED"))
        Metrics.counter("payments.completed").increment();
      if (submission && !replay && status.equals("REJECTED"))
        Metrics.counter("payments.rejected", "code", code).increment();
      if (code.equals("INTERNAL_ERROR"))
        Metrics.counter("payments.unexpected.failures").increment();
      org.jboss.logmanager.MDC.put("correlationId", correlation);
      if (payment != null) org.jboss.logmanager.MDC.put("paymentId", payment);
      org.jboss.logmanager.MDC.put("status", status);
      org.jboss.logmanager.MDC.put("code", code);
      org.jboss.logmanager.MDC.put("latencyMs", Long.toString(nanos / 1000000));
      LOG.info("Payment API outcome");
    } catch (RuntimeException ignored) {
    } finally {
      org.jboss.logmanager.MDC.clear();
    }
  }
}
