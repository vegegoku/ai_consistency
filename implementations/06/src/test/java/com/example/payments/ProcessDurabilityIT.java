package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ProcessDurabilityIT {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payments")
          .withUsername("payments")
          .withPassword("test-only");

  HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  int port() throws Exception {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  Process start(int port, int iteration) throws Exception {
    var args = new ArrayList<String>();
    args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    args.addAll(
        List.of(
            "-Dquarkus.http.port=" + port,
            "-Dquarkus.datasource.jdbc.url=" + postgres.getJdbcUrl(),
            "-Dquarkus.datasource.username=payments",
            "-Dquarkus.datasource.password=test-only",
            "-Dpayment.require-https=false",
            "-Dpayment.requests-per-minute=20000",
            "-Dquarkus.otel.sdk.disabled=true",
            "-Dmp.jwt.verify.publickey.location="
                + Path.of("src/test/resources/test-public.pem").toAbsolutePath(),
            "-Dmp.jwt.verify.issuer=https://issuer.test",
            "-jar",
            "target/quarkus-app/quarkus-run.jar"));
    Process p =
        new ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(Path.of("target/restart-" + iteration + ".log").toFile())
            .start();
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < deadline) {
      if (!p.isAlive())
        throw new IllegalStateException(
            Files.readString(Path.of("target/restart-" + iteration + ".log")));
      try {
        var r =
            client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/health/ready"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) return p;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    p.destroyForcibly();
    throw new IllegalStateException("Application did not become ready");
  }

  void stop(Process p) throws Exception {
    p.destroy();
    if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly().waitFor();
  }

  @Test
  void committedTransferSurvivesActualJvmRestart() throws Exception {
    int port = port();
    String base = "http://localhost:" + port;
    String id = UUID.randomUUID().toString();
    String token =
        new JwtAuthenticationTest()
            .token(
                "https://issuer.test",
                "payment-api",
                Instant.now().getEpochSecond() + 600,
                "payments:submit payments:read accounts:read");
    String body =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\""
            + Instant.now()
            + "\",\"purpose\":\"Restart durability\"}";
    var post =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/payments"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    String original;
    Process first = start(port, 1);
    try {
      try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "payments", "test-only");
          var statement = c.createStatement()) {
        statement.execute(
            Files.readString(Path.of("src/main/resources/db/dev/V2__development_fixtures.sql")));
      }
      var response = client.send(post, HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode(), response.body());
      original = response.body();
    } finally {
      stop(first);
    }
    Process second = start(port, 2);
    try {
      var status =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/api/v1/payments/" + id))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, status.statusCode(), status.body());
      assertEquals(original, status.body());
      var replay = client.send(post, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, replay.statusCode(), replay.body());
      assertEquals(original, replay.body());
      try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "payments", "test-only");
          var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT available_balance,(SELECT count(*) FROM ledger_entries) FROM accounts"
                      + " WHERE id='ACC-1001'")) {
        r.next();
        assertEquals("374.250", r.getBigDecimal(1).toPlainString());
        assertEquals(2, r.getLong(2));
      }
      var load = new ProcessBuilder("python3", "scripts/load-test.py").redirectErrorStream(true);
      load.environment().put("BASE_URL", base);
      load.environment().put("TOKEN", token);
      load.environment().put("LOAD_SECONDS", "5");
      load.environment().put("LOAD_RPS", "100");
      var loadProcess = load.start();
      String report =
          new String(
              loadProcess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, loadProcess.waitFor(), report);
      Files.writeString(Path.of("target/load-results.json"), report);
      System.out.println("Reference load result: " + report);
    } finally {
      stop(second);
    }
  }
}
