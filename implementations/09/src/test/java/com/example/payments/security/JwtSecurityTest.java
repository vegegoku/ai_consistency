package com.example.payments.security;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtSecurityTest {
  HttpServer server;
  RSAKey key;
  JwtDecoder decoder;

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        exchange -> {
          byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    decoder =
        new SecurityConfiguration()
            .jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                "https://issuer.example",
                "payments-api");
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  String token(String issuer, String audience, Instant expires, RSAKey signer) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("alice")
            .audience(audience)
            .issueTime(new Date())
            .claim("scope", "payments:write");
    if (expires != null) claims.expirationTime(Date.from(expires));
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
    jwt.sign(new RSASSASigner(signer));
    return jwt.serialize();
  }

  @Test
  void acceptsValidSignatureIssuerAudienceAndExpiry() throws Exception {
    assertThat(
            decoder
                .decode(
                    token(
                        "https://issuer.example",
                        "payments-api",
                        Instant.now().plusSeconds(60),
                        key))
                .getSubject())
        .isEqualTo("alice");
  }

  @Test
  void rejectsIssuerAudienceExpirationAndMissingExpiration() throws Exception {
    for (String t :
        List.of(
            token("wrong", "payments-api", Instant.now().plusSeconds(60), key),
            token("https://issuer.example", "wrong", Instant.now().plusSeconds(60), key),
            token("https://issuer.example", "payments-api", Instant.now().minusSeconds(120), key),
            token("https://issuer.example", "payments-api", null, key)))
      assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsForgedSignature() throws Exception {
    String t =
        token(
            "https://issuer.example",
            "payments-api",
            Instant.now().plusSeconds(60),
            new RSAKeyGenerator(2048).generate());
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtException.class);
  }
}
