package com.example.payments.security;

import java.util.Set;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record Caller(String subject, String customerId, Set<String> scopes) {
  public boolean has(String scope) {
    return scopes.contains(scope);
  }

  public boolean admin() {
    return has("payments:admin");
  }

  public static Caller from(JwtAuthenticationToken token) {
    var scopes =
        token.getAuthorities().stream()
            .map(a -> a.getAuthority().replaceFirst("^SCOPE_", ""))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return new Caller(token.getName(), token.getToken().getClaimAsString("customer_id"), scopes);
  }
}
