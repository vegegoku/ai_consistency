package com.example.payments.observability;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  private String correlation = UUID.randomUUID().toString();
  private final long started = System.nanoTime();
  private String paymentId;
  private String code = "NONE";

  public String correlation() {
    return correlation;
  }

  public void correlation(String value) {
    correlation = value;
  }

  public long started() {
    return started;
  }

  public String paymentId() {
    return paymentId;
  }

  public void paymentId(String value) {
    paymentId = value;
  }

  public String code() {
    return code;
  }

  public void code(String value) {
    code = value;
  }
}
