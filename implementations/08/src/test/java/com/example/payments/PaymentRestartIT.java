package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import io.smallrye.jwt.build.Jwt;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Executes the packaged application in separate processes against the same durable PostgreSQL. */
class PaymentRestartIT {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final Path log = Path.of("target/restart-application.log").toAbsolutePath();
  private String base, token;

  private Process start(PostgreSQLContainer<?> db, int port) throws Exception {
    var builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-jar",
            Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath().toString());
    var env = builder.environment();
    env.put("DATABASE_URL", db.getJdbcUrl());
    env.put("DATABASE_USER", db.getUsername());
    env.put("DATABASE_PASSWORD", db.getPassword());
    env.put("JWT_ISSUER", "https://test.example");
    env.put(
        "JWT_PUBLIC_KEY_LOCATION",
        Path.of("target/test-classes/publicKey.pem").toAbsolutePath().toString());
    env.put("QUARKUS_HTTP_INSECURE_REQUESTS", "enabled");
    env.put("QUARKUS_HTTP_PORT", Integer.toString(port));
    env.put("QUARKUS_OTEL_SDK_DISABLED", "true");
    builder
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    var process = builder.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive())
        throw new AssertionError("Application exited: " + Files.readString(log));
      try {
        if (client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/q/health/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode()
            == 200) return process;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    process.destroyForcibly();
    throw new AssertionError("Startup deadline expired: " + Files.readString(log));
  }

  private HttpResponse<String> request(String method, String path, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String body(String id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\""
        + amount
        + "\",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"Process restart verification\"}";
  }

  @Test
  void packagedServiceSurvivesRestartAndMeasuredLoad() throws Exception {
    try (var db = new PostgreSQLContainer<>("postgres:16-alpine")) {
      db.start();
      int port;
      try (var socket = new java.net.ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      base = "http://localhost:" + port;
      token =
          Jwt.issuer("https://test.example")
              .audience("payment-service")
              .subject("alice")
              .claim("scope", "payments:write payments:read balances:read")
              .expiresAt(Instant.now().plusSeconds(600))
              .sign("privateKey.pem");
      Process process = start(db, port);
      try {
        var data = new PGSimpleDataSource();
        data.setURL(db.getJdbcUrl());
        data.setUser(db.getUsername());
        data.setPassword(db.getPassword());
        try (var c = data.getConnection();
            var s = c.createStatement()) {
          s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
          s.execute(
              "INSERT INTO accounts(id,customer_id,available_balance,opening_balance)"
                  + " VALUES('A','alice',500,500),('B','bob',100,100)");
        }
        String id = UUID.randomUUID().toString(), instruction = body(id, "125.750");
        var first = request("POST", "/api/v1/payments", instruction);
        assertEquals(201, first.statusCode(), first.body());
        process.destroy();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        process = start(db, port);
        assertEquals(first.body(), request("GET", "/api/v1/payments/" + id, null).body());
        var replay = request("POST", "/api/v1/payments", instruction);
        assertEquals(200, replay.statusCode());
        assertEquals(first.body(), replay.body());
        assertTrue(request("GET", "/api/v1/accounts/A/balance", null).body().contains("374.250"));
        int count = 300;
        var timings = new ArrayList<Long>();
        try (var pool = Executors.newFixedThreadPool(32)) {
          var results = new ArrayList<Future<Long>>();
          long start = System.nanoTime();
          for (int i = 0; i < count; i++) {
            long target = start + i * 10_000_000L;
            long wait = target - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            results.add(
                pool.submit(
                    () -> {
                      long before = System.nanoTime();
                      var response =
                          request(
                              "POST",
                              "/api/v1/payments",
                              body(UUID.randomUUID().toString(), "0.001"));
                      assertEquals(201, response.statusCode(), response.body());
                      return System.nanoTime() - before;
                    }));
          }
          for (var result : results) timings.add(result.get());
        }
        Collections.sort(timings);
        double p95 = timings.get((int) Math.ceil(count * .95) - 1) / 1_000_000.0;
        Files.writeString(
            Path.of("target/load-result.txt"),
            "300 unique transfers; arrival rate 100 requests/second; p95="
                + p95
                + " ms; all 201; local shared development host; not a production SLA\n");
        assertTrue(p95 < 500, "Reference local p95 exceeded target: " + p95 + "ms");
        try (var c = data.getConnection();
            var s = c.createStatement();
            var r = s.executeQuery("SELECT count(*),sum(signed_amount) FROM ledger_entries")) {
          r.next();
          assertEquals(602, r.getInt(1));
          assertEquals(0, r.getBigDecimal(2).signum());
        }
      } finally {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
      }
    }
  }
}
