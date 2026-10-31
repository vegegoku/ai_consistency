package com.example.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;

final class IdentityServer {
  private HttpServer server;

  Map<String, String> start() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var pub = (RSAPublicKey) keys.getPublic();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
      String jwks =
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "keys",
                      List.of(
                          Map.of(
                              "kty",
                              "RSA",
                              "kid",
                              "test",
                              "alg",
                              "RS256",
                              "use",
                              "sig",
                              "n",
                              unsigned(pub.getModulus().toByteArray()),
                              "e",
                              unsigned(pub.getPublicExponent().toByteArray())))));
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] body = jwks.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      System.setProperty(
          "payment.test.private-key",
          Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
      System.setProperty("payment.test.issuer", issuer);
      return Map.of(
          "quarkus.oidc.enabled",
          "true",
          "quarkus.oidc.auth-server-url",
          issuer,
          "quarkus.oidc.discovery-enabled",
          "false",
          "quarkus.oidc.jwks-path",
          "/jwks",
          "quarkus.oidc.token.issuer",
          issuer);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String unsigned(byte[] b) {
    if (b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  void stop() {
    if (server != null) server.stop(0);
    System.clearProperty("payment.test.private-key");
    System.clearProperty("payment.test.issuer");
  }

  static String token(Map<String, Object> overrides) {
    try {
      var claims = new HashMap<String, Object>();
      claims.put("iss", System.getProperty("payment.test.issuer"));
      claims.put("aud", "payment-service");
      claims.put("sub", "alice");
      claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
      claims.put("iat", Instant.now().getEpochSecond());
      claims.put("scope", "payments:read payments:write");
      claims.putAll(overrides);
      var json = new ObjectMapper();
      var encoder = Base64.getUrlEncoder().withoutPadding();
      String header =
          encoder.encodeToString(
              json.writeValueAsBytes(Map.of("alg", "RS256", "kid", "test", "typ", "JWT")));
      String payload = encoder.encodeToString(json.writeValueAsBytes(claims));
      var key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(
                      Base64.getDecoder().decode(System.getProperty("payment.test.private-key"))));
      var signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(
          (header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      return header + "." + payload + "." + encoder.encodeToString(signature.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
