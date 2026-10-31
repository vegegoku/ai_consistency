package com.example.payments.api;

import com.example.payments.domain.*;
import java.time.Instant;
import java.util.*;

public final class Errors {
  private Errors() {}

  public static Map<String, Object> body(PaymentException e, String correlation) {
    var m = new LinkedHashMap<String, Object>();
    if (e.paymentId() != null) m.put("paymentId", e.paymentId());
    m.put("status", "ERROR");
    m.put("code", e.code());
    m.put("message", "The request could not be completed.");
    m.put("issues", e.issues());
    m.put("correlationId", correlation);
    m.put("timestamp", Instant.now().toString());
    return m;
  }
}
