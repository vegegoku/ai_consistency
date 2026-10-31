package com.example.payments.domain;

import java.util.List;

public final class PaymentException extends RuntimeException {
  private final int http;
  private final String code;
  private final List<Issue> issues;
  private final String paymentId;

  public PaymentException(int http, String code) {
    this(http, code, List.of(), null);
  }

  public PaymentException(int http, String code, List<Issue> issues, String paymentId) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
    this.paymentId = paymentId;
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
