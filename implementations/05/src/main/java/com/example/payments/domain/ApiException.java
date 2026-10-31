package com.example.payments.domain;

import java.util.List;

public class ApiException extends RuntimeException {
  private final java.util.UUID paymentId;
  private final int http;
  private final String code;
  private final List<Issue> issues;

  public ApiException(int http, String code) {
    this(http, code, List.of());
  }

  public ApiException(int http, String code, List<Issue> issues) {
    this(http, code, issues, null);
  }

  public ApiException(int http, String code, List<Issue> issues, java.util.UUID paymentId) {
    super("The request could not be completed.");
    this.paymentId = paymentId;
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
  }

  public java.util.UUID paymentId() {
    return paymentId;
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
}
