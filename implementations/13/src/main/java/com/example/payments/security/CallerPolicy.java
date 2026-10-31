package com.example.payments.security;

import com.example.payments.domain.ApiFailure;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class CallerPolicy {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;

  public String require(String scope) {
    if (identity.isAnonymous()) throw new ApiFailure(401, "UNAUTHENTICATED");
    Object raw = jwt.getClaim("scope");
    Set<String> scopes =
        raw instanceof String s ? new HashSet<>(Arrays.asList(s.split(" +"))) : Set.of();
    if (!scopes.contains(scope)) throw new ApiFailure(403, "FORBIDDEN");
    String principal = jwt.getSubject();
    if (principal == null || principal.isBlank() || principal.length() > 255)
      throw new ApiFailure(401, "UNAUTHENTICATED");
    return principal;
  }
}
