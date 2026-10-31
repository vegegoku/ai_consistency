package com.example.payments.domain;

import java.time.Instant;
import java.util.*;

public final class Problem extends RuntimeException {
  public final int http;
  public final String code;
  public final String paymentId;
  public final List<Map<String, String>> issues;

  public Problem(int http, String code) {
    this(http, code, null, List.of());
  }

  public Problem(int http, String code, String id, List<Map<String, String>> issues) {
    super("The payment could not be completed.");
    this.http = http;
    this.code = code;
    this.paymentId = id;
    this.issues = List.copyOf(issues);
  }

  public Map<String, Object> body(String correlation) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (paymentId != null) b.put("paymentId", paymentId);
    b.put("status", http == 409 || http == 403 ? "REJECTED" : "ERROR");
    b.put("code", code);
    b.put("message", getMessage());
    b.put("issues", issues);
    b.put("correlationId", correlation);
    b.put("timestamp", Instant.now().toString());
    return b;
  }

  public static Map<String, String> issue(String f, String c, String m) {
    return Map.of("field", f, "code", c, "message", m);
  }
}
