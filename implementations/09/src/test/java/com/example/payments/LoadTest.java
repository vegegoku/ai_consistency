package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real HTTP/JWT/PostgreSQL reference-host smoke load, including scheduling delay. */
@Testcontainers
class LoadTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void measuresOneHundredRequestsPerSecond() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("load").generate();
    HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/jwks",
        exchange -> {
          byte[] b = new JWKSet(key.toPublicJWK()).toString().getBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, b.length);
          exchange.getResponseBody().write(b);
          exchange.close();
        });
    jwks.start();
    String[] args = {
      "--server.port=0",
      "--spring.datasource.url=" + postgres.getJdbcUrl(),
      "--spring.datasource.username=" + postgres.getUsername(),
      "--spring.datasource.password=" + postgres.getPassword(),
      "--payments.require-tls=false",
      "--spring.flyway.locations=classpath:db/migration,classpath:db/dev",
      "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
          + jwks.getAddress().getPort()
          + "/jwks",
      "--spring.security.oauth2.resourceserver.jwt.issuer-uri=https://load.example",
      "--logging.level.com.example.payments.application.PaymentService=WARN"
    };
    try (var context = new SpringApplicationBuilder(PaymentApplication.class).run(args);
        var client = HttpClient.newHttpClient();
        var pool = Executors.newFixedThreadPool(32)) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("load").build(),
              new JWTClaimsSet.Builder()
                  .subject("alice")
                  .issuer("https://load.example")
                  .audience("payments-api")
                  .issueTime(new Date())
                  .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                  .claim("scope", "payments:write")
                  .build());
      jwt.sign(new RSASSASigner(key));
      String token = jwt.serialize();
      URI url = URI.create("http://localhost:" + port + "/api/v1/payments");
      // Resolve JWKS, initialize HTTP connections and warm JVM before the measured interval.
      assertThat(
              client.send(request(url, token), HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(201);
      int count = 300;
      long start = System.nanoTime() + 100_000_000;
      List<Future<Long>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        long due = start + i * 10_000_000L;
        futures.add(
            pool.submit(
                () -> {
                  long remaining;
                  while ((remaining = due - System.nanoTime()) > 0)
                    LockSupport.parkNanos(remaining);
                  var response =
                      client.send(request(url, token), HttpResponse.BodyHandlers.ofString());
                  assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
                  return System.nanoTime() - due;
                }));
      }
      List<Long> durations = new ArrayList<>();
      for (var f : futures) durations.add(f.get(30, TimeUnit.SECONDS));
      durations.sort(Long::compare);
      double p95 = durations.get((int) Math.ceil(count * .95) - 1) / 1_000_000d;
      String report =
          "300 successful real HTTP transfers; offered rate=100/s; JWT RS256 validated; PostgreSQL"
              + " 16; p95="
              + String.format(java.util.Locale.ROOT, "%.3f", p95)
              + " ms; host="
              + System.getProperty("os.name")
              + " "
              + System.getProperty("os.arch")
              + "; Java="
              + System.getProperty("java.version")
              + "\n";
      Files.createDirectories(Path.of("target"));
      Files.writeString(Path.of("target/load-report.txt"), report);
      assertThat(p95).as(report).isLessThan(500);
      var reconciliation =
          context.getBean(com.example.payments.application.PaymentService.class).reconcile();
      assertThat(reconciliation.get("balanced")).isEqualTo(true);
    } finally {
      jwks.stop(0);
    }
  }

  private HttpRequest request(URI url, String token) {
    String body =
        "{\"paymentId\":\""
            + UUID.randomUUID()
            + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":0.001,\"date\":\""
            + Instant.now()
            + "\",\"purpose\":\"load test\"}";
    return HttpRequest.newBuilder(url)
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }
}
