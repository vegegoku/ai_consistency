package com.example.payments.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtVerificationTest {
  static RSAKey signing, other;
  static HttpServer jwks;
  JwtDecoder decoder;

  @BeforeAll
  static void start() throws Exception {
    signing = new RSAKeyGenerator(2048).keyID("test").generate();
    other = new RSAKeyGenerator(2048).keyID("test").generate();
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/jwks",
        exchange -> {
          byte[] bytes = new JWKSet(signing.toPublicJWK()).toString().getBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    jwks.start();
  }

  @AfterAll
  static void stop() {
    jwks.stop(0);
  }

  @BeforeEach
  void decoder() {
    decoder =
        new SecurityConfiguration()
            .jwtDecoder(
                "https://issuer.example",
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                "payments-api");
  }

  String token(RSAKey key, String issuer, String audience, Instant expiry) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject("alice")
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(expiry))
            .issueTime(Date.from(Instant.now()))
            .claim("scope", "payments:write")
            .build();
    var token =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
    token.sign(new RSASSASigner(key));
    return token.serialize();
  }

  @Test
  void acceptsOnlyCorrectSignatureIssuerAudienceAndExpiry() throws Exception {
    assertEquals(
        "alice",
        decoder
            .decode(
                token(
                    signing,
                    "https://issuer.example",
                    "payments-api",
                    Instant.now().plusSeconds(120)))
            .getSubject());
    for (var bad :
        List.of(
            token(other, "https://issuer.example", "payments-api", Instant.now().plusSeconds(120)),
            token(signing, "https://evil.example", "payments-api", Instant.now().plusSeconds(120)),
            token(signing, "https://issuer.example", "wrong", Instant.now().plusSeconds(120)),
            token(
                signing,
                "https://issuer.example",
                "payments-api",
                Instant.now().minusSeconds(120))))
      assertThrows(JwtException.class, () -> decoder.decode(bad));
  }
}
