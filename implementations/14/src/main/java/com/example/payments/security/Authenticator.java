package com.example.payments.security;

import com.example.payments.domain.ApiException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class Authenticator {
  private final JWKSet keys;
  private final String issuer, audience;
  private final Clock clock;

  public Authenticator(String jwksFile, String issuer, String audience, Clock clock)
      throws Exception {
    keys = JWKSet.parse(Files.readString(Path.of(jwksFile)));
    if (keys.getKeys().isEmpty() || keys.getKeys().stream().anyMatch(JWK::isPrivate))
      throw new IllegalArgumentException("A public-only RS256 JWKS is required");
    this.issuer = issuer;
    this.audience = audience;
    this.clock = clock;
  }

  public Caller authenticate(String authorization) {
    try {
      if (authorization == null
          || !authorization.startsWith("Bearer ")
          || authorization.length() > 8192) throw new IllegalArgumentException();
      SignedJWT token = SignedJWT.parse(authorization.substring(7));
      if (token.getHeader().getKeyID() == null
          || !JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())
          || token.getHeader().getCriticalParams() != null) throw new IllegalArgumentException();
      JWK jwk = keys.getKeyByKeyId(token.getHeader().getKeyID());
      if (!(jwk instanceof RSAKey rsa)
          || rsa.size() < 2048
          || !token.verify(new RSASSAVerifier(rsa.toRSAPublicKey())))
        throw new IllegalArgumentException();
      var claims = token.getJWTClaimsSet();
      Instant now = clock.instant();
      if (!issuer.equals(claims.getIssuer())
          || !claims.getAudience().contains(audience)
          || claims.getExpirationTime() == null
          || !claims.getExpirationTime().toInstant().isAfter(now)
          || claims.getNotBeforeTime() != null
              && claims.getNotBeforeTime().toInstant().isAfter(now))
        throw new IllegalArgumentException();
      String subject = claims.getSubject();
      if (subject == null
          || subject.isBlank()
          || subject.length() > 200
          || subject.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException();
      String scope = claims.getStringClaim("scope");
      Set<String> scopes =
          scope == null ? Set.of() : new HashSet<>(Arrays.asList(scope.split(" +")));
      return new Caller(subject, Set.copyOf(scopes));
    } catch (Exception e) {
      throw new ApiException(401, "UNAUTHENTICATED");
    }
  }
}
