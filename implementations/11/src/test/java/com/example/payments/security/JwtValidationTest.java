package com.example.payments.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtValidationTest {
  @Test
  void validatesSignatureIssuerAudienceAndExpiration() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("test").generate();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        exchange -> {
          byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      JwtDecoder decoder =
          new SecurityConfiguration()
              .jwtDecoder(
                  "https://issuer.example",
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                  "payment-service");
      assertEquals(
          "alice",
          decoder
              .decode(
                  token(
                      key,
                      "https://issuer.example",
                      "payment-service",
                      Instant.now().plusSeconds(600)))
              .getSubject());
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(
                      key,
                      "https://wrong.example",
                      "payment-service",
                      Instant.now().plusSeconds(600))));
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(key, "https://issuer.example", "wrong", Instant.now().plusSeconds(600))));
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(
                      key,
                      "https://issuer.example",
                      "payment-service",
                      Instant.now().minusSeconds(600))));
      RSAKey wrong = new RSAKeyGenerator(2048).keyID("test").generate();
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  token(
                      wrong,
                      "https://issuer.example",
                      "payment-service",
                      Instant.now().plusSeconds(600))));
    } finally {
      server.stop(0);
    }
  }

  String token(RSAKey key, String issuer, String aud, Instant expiry) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
            new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(issuer)
                .audience(aud)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(expiry))
                .claim("scope", "payments:write payments:read")
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
