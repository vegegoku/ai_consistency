package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import io.smallrye.jwt.build.Jwt;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real packaged-service restart: no in-process payment or idempotency state survives. */
class RestartDurabilityIT {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private String base, token;

  private HttpResponse<String> request(String method, String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Process start(PostgreSQLContainer<?> pg, int port, int run) throws Exception {
    var process =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            "target/quarkus-app/quarkus-run.jar");
    var env = process.environment();
    env.putAll(
        Map.of(
            "DB_URL",
            pg.getJdbcUrl(),
            "DB_USER",
            pg.getUsername(),
            "DB_PASSWORD",
            pg.getPassword(),
            "JWT_PUBLIC_KEY",
            Path.of("src/test/resources/publicKey.pem").toAbsolutePath().toUri().toString(),
            "JWT_ISSUER",
            "https://issuer.test",
            "QUARKUS_HTTP_PORT",
            Integer.toString(port),
            "QUARKUS_HTTP_INSECURE_REQUESTS",
            "enabled",
            "PAYMENT_REQUIRE_TLS",
            "false",
            "QUARKUS_OTEL_SDK_DISABLED",
            "true"));
    var running =
        process
            .redirectErrorStream(true)
            .redirectOutput(Path.of("target/durability-app-" + run + ".log").toFile())
            .start();
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < deadline) {
      if (!running.isAlive())
        throw new IllegalStateException(
            "Packaged app exited; inspect target/durability-app-" + run + ".log");
      try {
        var health =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/q/health/ready"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (health.statusCode() == 200) return running;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    stop(running);
    throw new IllegalStateException("Readiness deadline exceeded");
  }

  private void stop(Process process) throws Exception {
    process.destroy();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void committedReceiptLedgerAndBalancesSurviveProcessRestart() throws Exception {
    try (var pg =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments")
            .withUsername("test")
            .withPassword("test")) {
      pg.start();
      int port;
      try (var socket = new java.net.ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      base = "http://localhost:" + port;
      String pem =
          Files.readString(Path.of("src/test/resources/privateKey.pem"))
              .replaceAll("-----[^-]+-----", "")
              .replaceAll("\\s", "");
      PrivateKey key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      token =
          Jwt.issuer("https://issuer.test")
              .subject("alice")
              .audience("payments-api")
              .claim("scope", "payments:write payments:read accounts:read")
              .expiresAt(Instant.now().getEpochSecond() + 600)
              .sign(key);
      var first = start(pg, port, 1);
      String id = UUID.randomUUID().toString();
      String body =
          "{\"paymentId\":\""
              + id
              + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\""
              + Instant.now()
              + "\",\"purpose\":\"Restart durability\"}";
      String receipt;
      try {
        try (var c =
                DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var s = c.createStatement()) {
          s.execute(Files.readString(Path.of("src/test/resources/test-seed.sql")));
        }
        var response = request("POST", "/api/v1/payments", body);
        assertEquals(201, response.statusCode(), response.body());
        receipt = response.body();
      } finally {
        stop(first);
      }
      var second = start(pg, port, 2);
      try {
        var lookup = request("GET", "/api/v1/payments/" + id, null);
        assertEquals(200, lookup.statusCode(), lookup.body());
        assertEquals(receipt, lookup.body());
        var replay = request("POST", "/api/v1/payments", body);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(receipt, replay.body());
        assertTrue(
            request("GET", "/api/v1/accounts/ACC-1001/balance", null).body().contains("374.250"));
        try (var c =
                DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var s = c.createStatement();
            var rs = s.executeQuery("SELECT count(*),sum(signed_amount) FROM ledger_entries")) {
          rs.next();
          assertEquals(2, rs.getInt(1));
          assertEquals(0, rs.getBigDecimal(2).signum());
        }
      } finally {
        stop(second);
      }
    }
  }
}
