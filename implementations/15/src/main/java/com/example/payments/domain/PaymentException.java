package com.example.payments.domain;

import java.util.*;

public class PaymentException extends RuntimeException {
  private final int httpStatus;
  private final String code;
  private final List<Issue> issues;
  private final UUID paymentId;

  public PaymentException(int httpStatus, String code, String message) {
    this(httpStatus, code, message, List.of(), null);
  }

  public PaymentException(
      int httpStatus, String code, String message, List<Issue> issues, UUID paymentId) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.issues = List.copyOf(issues);
    this.paymentId = paymentId;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public String code() {
    return code;
  }

  public List<Issue> issues() {
    return issues;
  }

  public UUID paymentId() {
    return paymentId;
  }
}
