package com.example.payments.api;

import com.example.payments.domain.*;
import java.time.Instant;
import java.util.*;

public final class ErrorBodies {
  private ErrorBodies() {}

  public static Map<String, Object> create(
      PaymentException error, String correlationId, Instant now) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (error.paymentId() != null) body.put("paymentId", error.paymentId().toString());
    body.put("status", "ERROR");
    body.put("code", error.code());
    body.put("message", error.getMessage());
    body.put("issues", error.issues());
    body.put("correlationId", correlationId);
    body.put("timestamp", now.toString());
    return body;
  }
}
