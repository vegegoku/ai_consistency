package com.example.payments.domain;

import java.util.List;

public class ApiException extends RuntimeException {
  public final int http;
  public final String code;
  public final String paymentId;
  public final List<Issue> issues;

  public record Issue(String field, String code, String message) {}

  public ApiException(int http, String code) {
    this(http, code, null, List.of());
  }

  public ApiException(int http, String code, String id, List<Issue> issues) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.paymentId = id;
    this.issues = List.copyOf(issues);
  }
}
