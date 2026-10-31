package com.example.payments.domain;

import java.util.List;

public class PaymentException extends RuntimeException {
  public record Issue(String field, String code, String message) {}

  public final int http;
  public final String code;
  public final List<Issue> issues;
  public final String paymentId;

  public PaymentException(int http, String code) {
    this(http, code, List.of(), null);
  }

  public PaymentException(int http, String code, List<Issue> issues, String id) {
    super(code);
    this.http = http;
    this.code = code;
    this.issues = List.copyOf(issues);
    this.paymentId = id;
  }
}
