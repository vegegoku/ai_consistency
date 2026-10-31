package com.example.payments.observability;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  public String correlationId = UUID.randomUUID().toString();
  public String paymentId;
  public String status = "ERROR";
  public String code = "NONE";
  public long started = System.nanoTime();
}
