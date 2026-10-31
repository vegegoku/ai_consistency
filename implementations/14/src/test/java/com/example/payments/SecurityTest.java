package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.domain.ApiException;
import com.example.payments.security.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SecurityTest {
  @TempDir Path dir;
  RSAKey key;
  Authenticator auth;

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    var path = dir.resolve("jwks.json");
    Files.writeString(path, new JWKSet(key.toPublicJWK()).toString());
    auth = new Authenticator(path.toString(), "https://issuer", "payments", Clock.systemUTC());
  }

  String token(String issuer, String audience, Instant expiration) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("alice")
                .expirationTime(Date.from(expiration))
                .claim("scope", "payments:submit payments:read")
                .build());
    jwt.sign(new RSASSASigner(key));
    return "Bearer " + jwt.serialize();
  }

  @Test
  void validJwtHasScopes() throws Exception {
    var user =
        auth.authenticate(token("https://issuer", "payments", Instant.now().plusSeconds(60)));
    assertEquals("alice", user.subject());
    assertTrue(user.permits("payments:submit"));
  }

  @Test
  void issuerAudienceExpiryAndSignatureEnforced() throws Exception {
    for (var token :
        List.of(
            token("wrong", "payments", Instant.now().plusSeconds(60)),
            token("https://issuer", "wrong", Instant.now().plusSeconds(60)),
            token("https://issuer", "payments", Instant.now().minusSeconds(1)),
            "Bearer invalid"))
      assertEquals(401, assertThrows(ApiException.class, () -> auth.authenticate(token)).http);
    var other = new RSAKeyGenerator(2048).keyID("test").generate();
    var jwt =
        SignedJWT.parse(
            token("https://issuer", "payments", Instant.now().plusSeconds(60)).substring(7));
    var forged = new SignedJWT(jwt.getHeader(), jwt.getJWTClaimsSet());
    forged.sign(new RSASSASigner(other));
    assertThrows(ApiException.class, () -> auth.authenticate("Bearer " + forged.serialize()));
    assertThrows(ApiException.class, () -> auth.authenticate(null));
  }

  @Test
  void rateLimitBoundedByPrincipal() {
    var limiter = new RateLimiter(2, Clock.systemUTC());
    limiter.check("alice");
    limiter.check("alice");
    assertEquals(429, assertThrows(ApiException.class, () -> limiter.check("alice")).http);
    limiter.check("bob");
  }

  @Test
  void privateVerificationKeysAreRejected() throws Exception {
    var file = dir.resolve("private-jwks.json");
    Files.writeString(file, new JWKSet(key).toString(false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Authenticator(file.toString(), "https://issuer", "payments", Clock.systemUTC()));
  }

  @Test
  void productionHttpsRequiresCertificateAndServesTls() throws Exception {
    var store = dir.resolve("server.p12");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "test-only-password",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "1")
            .redirectErrorStream(true)
            .start();
    process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    var config =
        new Config(
            "",
            "",
            "",
            0,
            false,
            "",
            "",
            "",
            65536,
            "10000.000",
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            100,
            "",
            "",
            2555);
    try (var telemetry = new com.example.payments.observability.Telemetry()) {
      assertThrows(
          IllegalStateException.class,
          () -> new com.example.payments.api.ApiServer(config, null, null, null, null, telemetry));
      var tlsConfig =
          new Config(
              "",
              "",
              "",
              0,
              false,
              "",
              "",
              "",
              65536,
              "10000.000",
              Duration.ofDays(30),
              Duration.ofMinutes(5),
              100,
              store.toString(),
              "test-only-password",
              2555);
      var codec =
          new com.example.payments.api.Codec(
              65536, "10000.000", Duration.ofDays(30), Duration.ofMinutes(5), Clock.systemUTC());
      var keys = java.security.KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keys.load(input, "test-only-password".toCharArray());
      }
      var trust =
          javax.net.ssl.TrustManagerFactory.getInstance(
              javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keys);
      var ssl = javax.net.ssl.SSLContext.getInstance("TLS");
      ssl.init(null, trust.getTrustManagers(), null);
      try (var api =
              new com.example.payments.api.ApiServer(
                  tlsConfig, codec, null, null, null, telemetry);
          var client = java.net.http.HttpClient.newBuilder().sslContext(ssl).build()) {
        api.start();
        var response =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://localhost:" + api.port() + "/health/live"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent());
      }
    }
  }
}
