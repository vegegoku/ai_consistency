package com.example.payments.domain;

import java.util.List;

public class PaymentException extends RuntimeException {
  public record Issue(String field, String code, String message) {}

  public final int http;
  public final String code;
  public final List<Issue> issues;
  public String paymentId;

  public PaymentException(int http, String code) {
    this(http, code, List.of());
  }

  public PaymentException(int http, String code, List<Issue> issues) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
  }
}
