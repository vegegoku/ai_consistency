package com.example.payments.security;

import java.util.Set;

public record Caller(String subject, Set<String> scopes) {
  public boolean permits(String scope) {
    return scopes.contains(scope);
  }
}
