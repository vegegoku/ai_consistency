package com.example.payments.security;

import com.example.payments.domain.Problem;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class Caller {
  @Inject JWTParser parser;
  private JsonWebToken token;

  public void authenticate(String authorization) {
    try {
      if (authorization == null || !authorization.startsWith("Bearer ")) throw new Exception();
      token = parser.parse(authorization.substring(7));
      if (token.getSubject() == null
          || token.getSubject().length() > 128
          || token.getExpirationTime() <= System.currentTimeMillis() / 1000) throw new Exception();
    } catch (Exception e) {
      throw new Problem(401, "UNAUTHENTICATED");
    }
  }

  public String principal() {
    return token.getSubject();
  }

  public void scope(String scope) {
    Object claim = token.getClaim("scope");
    if (claim == null || !Arrays.asList(claim.toString().split(" ")).contains(scope))
      throw new Problem(403, "FORBIDDEN");
  }
}
