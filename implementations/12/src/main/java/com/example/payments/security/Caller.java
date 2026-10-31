package com.example.payments.security;

import com.example.payments.domain.ApiException;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

public record Caller(String subject, Set<String> scopes) {
  public static Caller from(JsonWebToken jwt) {
    if (jwt == null
        || jwt.getSubject() == null
        || jwt.getSubject().isBlank()
        || jwt.getSubject().length() > 128)
      throw new ApiException(401, "UNAUTHENTICATED", "A valid bearer token is required.");
    long now = java.time.Instant.now().getEpochSecond();
    if (jwt.getExpirationTime() <= now || jwt.getIssuedAtTime() > now)
      throw new ApiException(401, "UNAUTHENTICATED", "A valid bearer token is required.");
    Object raw = jwt.getClaim("scope");
    Set<String> scopes = new HashSet<>();
    if (raw instanceof String value) scopes.addAll(Arrays.asList(value.split("\\s+")));
    return new Caller(jwt.getSubject(), Set.copyOf(scopes));
  }

  public void require(String scope) {
    if (!scopes.contains(scope))
      throw new ApiException(403, "FORBIDDEN", "The operation is not permitted.");
  }
}
