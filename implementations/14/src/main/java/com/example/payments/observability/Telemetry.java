package com.example.payments.observability;

import com.example.payments.api.Codec;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.*;
import java.util.*;

public final class Telemetry implements AutoCloseable {
  public final PrometheusMeterRegistry metrics =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  public void count(String name, String code) {
    metrics.counter(name, "code", code).increment();
  }

  public void event(String correlation, String payment, String status, String code, long nanos) {
    try {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("timestamp", java.time.Instant.now().toString());
      m.put("correlationId", correlation);
      if (payment != null) m.put("paymentId", payment);
      m.put("status", status);
      m.put("code", code);
      m.put("latencyMs", nanos / 1_000_000);
      System.out.println(Codec.JSON.writeValueAsString(m));
    } catch (Exception ignored) {
      /* Telemetry never changes a committed financial outcome. */
    }
  }

  @Override
  public void close() {
    metrics.close();
  }
}
