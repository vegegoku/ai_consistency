package com.example.payments.domain;

import java.util.Set;

public record Caller(String subject, Set<String> scopes) {
  public void require(String scope) {
    if (!scopes.contains(scope)) throw new ApiException(403, "FORBIDDEN");
  }
}
