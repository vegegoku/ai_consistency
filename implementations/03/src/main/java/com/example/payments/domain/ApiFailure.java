package com.example.payments.domain;

import java.util.List;

public class ApiFailure extends RuntimeException {
  public final int http;
  public final String code;
  public final List<Issue> issues;
  public String paymentId;

  public record Issue(String field, String code, String message) {}

  public ApiFailure(int http, String code) {
    this(http, code, List.of());
  }

  public ApiFailure(int http, String code, List<Issue> issues) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
  }

  public ApiFailure payment(String id) {
    paymentId = id;
    return this;
  }
}
