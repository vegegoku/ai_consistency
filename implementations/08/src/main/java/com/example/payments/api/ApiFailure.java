package com.example.payments.api;

import java.util.List;

public final class ApiFailure extends RuntimeException {
  public record Issue(String field, String code, String message) {}

  public final int http;
  public final String code;
  public final String paymentId;
  public final List<Issue> issues;

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

  public static ApiFailure business(int status, String code) {
    return new ApiFailure(status, code, "The payment could not be completed.");
  }
}
