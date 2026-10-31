package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.security.SecurityConfig;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtSecurityTest {
  static RSAKey key;
  static HttpServer server;
  static JwtDecoder decoder;

  @BeforeAll
  static void setup() throws Exception {
    var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    var pair = gen.generateKeyPair();
    key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("test-key")
            .build();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/keys",
        e -> {
          byte[] b = new JWKSet(key.toPublicJWK()).toString().getBytes();
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
    decoder =
        new SecurityConfig()
            .jwtDecoder(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/keys",
                "https://issuer.test",
                "payment-service");
  }

  @AfterAll
  static void close() {
    server.stop(0);
  }

  String token(String issuer, String audience, Instant expiry, boolean sign) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("alice")
            .issueTime(Date.from(Instant.now().minusSeconds(10)))
            .claim("scope", "payments:read payments:write");
    if (expiry != null) claims.expirationTime(Date.from(expiry));
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
    if (sign) jwt.sign(new RSASSASigner(key));
    else {
      var gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      jwt.sign(new RSASSASigner((RSAPrivateKey) gen.generateKeyPair().getPrivate()));
    }
    return jwt.serialize();
  }

  @Test
  void validSignatureAndClaims() throws Exception {
    assertEquals(
        "alice",
        decoder
            .decode(
                token(
                    "https://issuer.test", "payment-service", Instant.now().plusSeconds(300), true))
            .getSubject());
  }

  @Test
  void invalidIssuerAudienceExpiryAndSignatureRejected() throws Exception {
    for (String t :
        List.of(
            token("wrong", "payment-service", Instant.now().plusSeconds(300), true),
            token("https://issuer.test", "wrong", Instant.now().plusSeconds(300), true),
            token("https://issuer.test", "payment-service", Instant.now().minusSeconds(300), true),
            token("https://issuer.test", "payment-service", null, true),
            token("https://issuer.test", "payment-service", Instant.now().plusSeconds(300), false)))
      assertThrows(JwtException.class, () -> decoder.decode(t));
  }
}
