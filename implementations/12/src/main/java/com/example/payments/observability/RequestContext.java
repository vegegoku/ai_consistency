package com.example.payments.observability;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  private UUID correlationId = UUID.randomUUID();
  private final long started = System.nanoTime();

  public UUID correlationId() {
    return correlationId;
  }

  public void correlationId(UUID value) {
    correlationId = value;
  }

  public long started() {
    return started;
  }
}
