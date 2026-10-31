package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import io.smallrye.jwt.build.Jwt;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RestartIT {
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  Process app;
  String base, token;

  @Test
  void packagedApplicationRestartDurabilityAndReferenceLoad() throws Exception {
    System.setProperty("api.version", "1.44");
    try (var pg = new PostgreSQLContainer<>("postgres:14")) {
      pg.start();
      int port;
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      base = "http://localhost:" + port;
      token =
          Jwt.issuer("https://test.local")
              .subject("alice")
              .audience("payments")
              .claim("scope", "payments:write payments:read accounts:read")
              .expiresIn(300)
              .sign("privateKey.pem");
      try {
        start(pg, port);
        try (Connection c =
                DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Statement s = c.createStatement()) {
          s.execute(
              "INSERT INTO customers(id,display_name,status)"
                  + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
          s.execute(
              "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status)"
                  + " VALUES('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')");
        }
        String id = UUID.randomUUID().toString();
        String payload = payload(id, "125.750");
        var first = post(payload);
        assertEquals(201, first.statusCode(), first.body());
        String receipt = first.body();
        stop();
        start(pg, port);
        var status = get("/api/v1/payments/" + id);
        assertEquals(200, status.statusCode());
        assertEquals(receipt, status.body());
        var replay = post(payload);
        assertEquals(200, replay.statusCode());
        assertEquals(receipt, replay.body());
        assertTrue(get("/api/v1/accounts/A/balance").body().contains("374.250"));
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Future<Integer>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
          long epoch = System.nanoTime();
          for (int i = 0; i < 500; i++) {
            long target = epoch + i * 10_000_000L;
            while (System.nanoTime() < target) Thread.sleep(1);
            outcomes.add(
                pool.submit(
                    () -> {
                      long begin = System.nanoTime();
                      int code = post(payload(UUID.randomUUID().toString(), "0.001")).statusCode();
                      latencies.add((System.nanoTime() - begin) / 1_000_000);
                      return code;
                    }));
          }
          for (var outcome : outcomes) assertEquals(201, outcome.get(30, TimeUnit.SECONDS));
        }
        Collections.sort(latencies);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * .95) - 1);
        Files.writeString(
            Path.of("target/reference-load-report.txt"),
            "Packaged app + PostgreSQL 14 on local shared host: 100 requests/s for 5 seconds; 500"
                + " successful transfers; p95="
                + p95
                + "ms. Engineering target p95<500ms: "
                + (p95 < 500 ? "PASS" : "NOT MET")
                + ". Repeat on representative deployment hardware.\n");
        assertTrue(get("/api/v1/accounts/A/balance").body().contains("373.750"));
        try (Connection c =
                DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Statement s = c.createStatement();
            ResultSet r = s.executeQuery("SELECT count(*) FROM reconciliation_issues")) {
          r.next();
          assertEquals(0, r.getLong(1));
        }
      } finally {
        stop();
      }
    }
  }

  String payload(String id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\""
        + amount
        + "\",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"Restart and load verification\"}";
  }

  HttpResponse<String> post(String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/api/v1/payments"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  void start(PostgreSQLContainer<?> pg, int port) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m",
            "-jar",
            "target/quarkus-app/quarkus-run.jar");
    Map<String, String> env = builder.environment();
    env.put("DB_URL", pg.getJdbcUrl());
    env.put("DB_USER", pg.getUsername());
    env.put("DB_PASSWORD", pg.getPassword());
    env.put("QUARKUS_HTTP_PORT", Integer.toString(port));
    env.put("PAYMENTS_REQUIRE_TLS", "false");
    env.put("JWT_ISSUER", "https://test.local");
    env.put("JWT_AUDIENCE", "payments");
    env.put(
        "JWT_PUBLIC_KEY_LOCATION",
        Path.of("src/test/resources/publicKey.pem").toAbsolutePath().toUri().toString());
    env.put("QUARKUS_OTEL_SDK_DISABLED", "true");
    builder
        .redirectErrorStream(true)
        .redirectOutput(
            ProcessBuilder.Redirect.appendTo(Path.of("target/restart-app.log").toFile()));
    app = builder.start();
    for (int i = 0; i < 150; i++) {
      if (!app.isAlive())
        throw new IllegalStateException("Application exited; inspect target/restart-app.log");
      try {
        if (get("/q/health/ready").statusCode() == 200) return;
      } catch (Exception ignored) {
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("Readiness timeout");
  }

  void stop() throws Exception {
    if (app != null) {
      app.destroy();
      if (!app.waitFor(10, TimeUnit.SECONDS)) {
        app.destroyForcibly();
        app.waitFor();
      }
      app = null;
    }
  }
}
