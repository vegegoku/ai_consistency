package com.example.payments.domain;

import java.util.List;

public final class ApiFailure extends RuntimeException {
  private final int http;
  private final String code;
  private final List<Issue> issues;
  private final String paymentId;

  public ApiFailure(int http, String code, String message) {
    this(http, code, message, null, List.of());
  }

  public ApiFailure(int http, String code, String message, String paymentId, List<Issue> issues) {
    super(message);
    this.http = http;
    this.code = code;
    this.paymentId = paymentId;
    this.issues = List.copyOf(issues);
  }

  public int http() {
    return http;
  }

  public String code() {
    return code;
  }

  public List<Issue> issues() {
    return issues;
  }

  public String paymentId() {
    return paymentId;
  }
}
