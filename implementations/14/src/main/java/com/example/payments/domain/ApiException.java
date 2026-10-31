package com.example.payments.domain;

import java.util.*;

public final class ApiException extends RuntimeException {
  public final int http;
  public final String code;
  public final String paymentId;
  public final List<Issue> issues;

  public record Issue(String field, String code, String message) {}

  public ApiException(int http, String code, String paymentId, List<Issue> issues) {
    super("The request could not be completed.");
    this.http = http;
    this.code = code;
    this.paymentId = paymentId;
    this.issues = List.copyOf(issues);
  }

  public ApiException(int http, String code) {
    this(http, code, null, List.of());
  }

  public static ApiException business(int http, String code, String id) {
    return new ApiException(
        http, code, id, List.of(new Issue("payment", code, "The payment could not be completed.")));
  }
}
