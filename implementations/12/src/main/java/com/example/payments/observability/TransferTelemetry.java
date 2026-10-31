package com.example.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TransferTelemetry {
  @Inject MeterRegistry metrics;
  private static final Logger LOG = Logger.getLogger(TransferTelemetry.class);

  public void afterDebit() {
    Span.current().addEvent("payment.debit.applied");
  }

  public void committed(UUID id, String status, String code, boolean replay, long elapsedNanos) {
    try {
      metrics
          .counter("payments.outcomes", "status", status, "code", code == null ? "NONE" : code)
          .increment();
      metrics
          .timer("payments.processing")
          .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
      if (replay) metrics.counter("payments.replays").increment();
      LOG.infov(
          "paymentId={0} status={1} code={2} replay={3} latencyMs={4}",
          id, status, code, replay, elapsedNanos / 1000000);
    } catch (RuntimeException ignored) {
      LOG.warn("Post-commit telemetry unavailable");
    }
  }

  public void retry(String state) {
    try {
      metrics.counter("payments.retries").increment();
      if ("55P03".equals(state) || "57014".equals(state))
        metrics.counter("payments.lock.timeouts").increment();
    } catch (RuntimeException ignored) {
    }
  }
}
