package com.example.payments.domain;

import java.util.List;
import java.util.UUID;

public final class ApiException extends RuntimeException {
  private final int http;
  private final String code;
  private final UUID paymentId;
  private final List<Issue> issues;
  private final boolean definitive;

  public ApiException(int http, String code, String message) {
    this(http, code, message, null, List.of(), false);
  }

  public ApiException(
      int http,
      String code,
      String message,
      UUID paymentId,
      List<Issue> issues,
      boolean definitive) {
    super(message);
    this.http = http;
    this.code = code;
    this.paymentId = paymentId;
    this.issues = List.copyOf(issues);
    this.definitive = definitive;
  }

  public int http() {
    return http;
  }

  public String code() {
    return code;
  }

  public UUID paymentId() {
    return paymentId;
  }

  public List<Issue> issues() {
    return issues;
  }

  public boolean definitive() {
    return definitive;
  }
}
