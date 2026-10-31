package com.example.payments.domain;

public record Caller(String subject, boolean administrator) {
  public Caller {
    if (subject == null || subject.isBlank() || subject.length() > 128)
      throw new PaymentException(401, "UNAUTHENTICATED");
  }
}
