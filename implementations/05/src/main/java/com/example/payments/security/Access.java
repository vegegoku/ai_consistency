package com.example.payments.security;

import com.example.payments.domain.*;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class Access {
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;

  public Caller caller(String scope) {
    if (identity.isAnonymous()) throw new ApiException(401, "UNAUTHENTICATED");
    Set<String> scopes = new HashSet<>(identity.getRoles());
    Object raw = jwt.getClaim("scope");
    if (raw instanceof String s) scopes.addAll(Arrays.asList(s.split(" ")));
    var caller = new Caller(identity.getPrincipal().getName(), Set.copyOf(scopes));
    caller.require(scope);
    return caller;
  }
}
