package com.example.payments.security;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  private String correlationId = UUID.randomUUID().toString(),
      principal,
      paymentId,
      status = "ERROR",
      code = "NONE";
  private final long started = System.nanoTime();
  private boolean replay;

  public String correlationId() {
    return correlationId;
  }

  public void correlationId(String value) {
    correlationId = value;
  }

  public String principal() {
    return principal;
  }

  public void principal(String value) {
    principal = value;
  }

  public String paymentId() {
    return paymentId;
  }

  public void paymentId(String value) {
    paymentId = value;
  }

  public String status() {
    return status;
  }

  public void status(String value) {
    status = value;
  }

  public String code() {
    return code;
  }

  public void code(String value) {
    code = value;
  }

  public long started() {
    return started;
  }

  public boolean replay() {
    return replay;
  }

  public void replay(boolean value) {
    replay = value;
  }
}
