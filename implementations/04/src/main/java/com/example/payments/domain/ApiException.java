package com.example.payments.domain;

import java.util.List;

public class ApiException extends RuntimeException {
  public record Issue(String field, String code, String message) {}

  public final int http;
  public final String code;
  public final List<Issue> issues;
  public final java.util.UUID paymentId;

  public ApiException(int http, String code) {
    this(http, code, List.of());
  }

  public ApiException(int http, String code, List<Issue> issues) {
    this(http, code, issues, null);
  }

  public ApiException(int http, String code, List<Issue> issues, java.util.UUID paymentId) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
    this.paymentId = paymentId;
  }
}
