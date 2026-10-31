package com.example.payments.application;

import java.util.Map;

public record Outcome(int http, Map<String, Object> body, boolean replay) {
  public Outcome {
    body = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(body));
  }
}
