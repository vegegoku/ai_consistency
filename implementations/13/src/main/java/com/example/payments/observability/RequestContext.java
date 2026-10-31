package com.example.payments.observability;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  private final String correlationId = UUID.randomUUID().toString();
  private final long start = System.nanoTime();
  private String paymentId = "", code = "NONE";

  public String correlationId() {
    return correlationId;
  }

  public long start() {
    return start;
  }

  public String paymentId() {
    return paymentId;
  }

  public String code() {
    return code;
  }

  public void outcome(String id, String code) {
    if (id != null) this.paymentId = id;
    if (code != null) this.code = code;
  }
}
