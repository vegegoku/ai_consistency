package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Launches the packaged service twice against the same PostgreSQL volume. */
class RestartIT {
  Process process;
  HttpClient http = HttpClient.newHttpClient();

  void start(String url, String user, String password, Path key, int port) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    process =
        new ProcessBuilder(
                java,
                "-Dquarkus.http.port=" + port,
                "-Dquarkus.datasource.jdbc.url=" + url,
                "-Dquarkus.datasource.username=" + user,
                "-Dquarkus.datasource.password=" + password,
                "-Dmp.jwt.verify.publickey.location=" + key.toUri(),
                "-Dpayments.require-https=false",
                "-Dpayments.requests-per-minute=12000",
                "-jar",
                "target/quarkus-app/quarkus-run.jar")
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(Path.of("target/restart-service.log").toFile()))
            .start();
    for (int i = 0; i < 150; i++) {
      if (!process.isAlive())
        throw new IllegalStateException("Service exited; see target/restart-service.log");
      try {
        if (http.send(
                    HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/q/health/ready"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode()
            == 200) return;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("Service startup timeout");
  }

  void stop() throws Exception {
    if (process != null) {
      process.destroy();
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  String token(KeyPair key) throws Exception {
    var enc = Base64.getUrlEncoder().withoutPadding();
    long now = Instant.now().getEpochSecond();
    String h = enc.encodeToString("{\"alg\":\"RS256\"}".getBytes());
    String p =
        enc.encodeToString(
            new ObjectMapper()
                .writeValueAsBytes(
                    Map.of(
                        "sub",
                        "alice",
                        "iss",
                        "https://issuer.example.test",
                        "aud",
                        "payments",
                        "iat",
                        now,
                        "exp",
                        now + 3600,
                        "scope",
                        "payments:read payments:write")));
    String data = h + "." + p;
    var sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(key.getPrivate());
    sig.update(data.getBytes());
    return data + "." + enc.encodeToString(sig.sign());
  }

  HttpResponse<String> post(int port, String jwt, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/payments"))
            .header("Authorization", "Bearer " + jwt)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realRestartPreservesReceiptAndExactlyOnceEffect() throws Exception {
    try (var db = new PostgreSQLContainer<>("postgres:16-alpine")) {
      db.start();
      var gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      var keys = gen.generateKeyPair();
      var key = Files.createTempFile("restart-public-key", ".pem");
      Files.writeString(
          key,
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'})
                  .encodeToString(keys.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n");
      int port;
      try (var server = new java.net.ServerSocket(0)) {
        port = server.getLocalPort();
      }
      try {
        start(db.getJdbcUrl(), db.getUsername(), db.getPassword(), key, port);
        try (var c =
                DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var s = c.createStatement()) {
          s.execute(
              "INSERT INTO customers(id,display_name) VALUES ('alice','Alice'),('bob','Bob')");
          s.execute(
              "INSERT INTO"
                  + " accounts(id,customer_id,currency,available_balance,opening_balance,status)"
                  + " VALUES"
                  + " ('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
          s.execute("INSERT INTO account_permissions VALUES ('B','alice',false,true)");
        }
        String jwt = token(keys);
        String id = UUID.randomUUID().toString();
        String body =
            "{\"paymentId\":\""
                + id
                + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
                + Instant.now()
                + "\",\"purpose\":\"restart\"}";
        var first = post(port, jwt, body);
        assertEquals(201, first.statusCode(), first.body());
        stop();
        start(db.getJdbcUrl(), db.getUsername(), db.getPassword(), key, port);
        var replay = post(port, jwt, body);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(first.body(), replay.body());
        var receipt =
            http.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/payments/" + id))
                    .header("Authorization", "Bearer " + jwt)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, receipt.statusCode());
        assertEquals(first.body(), receipt.body());
        // Reference workload: 1,000 independent transfers dispatched at 100 requests/second.
        var samples = new ArrayList<java.util.concurrent.Future<Long>>();
        long loadStart = System.nanoTime();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
          for (int i = 0; i < 1000; i++) {
            long deadline = loadStart + i * 10_000_000L;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            String instruction =
                body.replace(id, UUID.randomUUID().toString()).replace("125.750", "0.001");
            final int endpointPort = port;
            samples.add(
                pool.submit(
                    () -> {
                      long begin = System.nanoTime();
                      var result = post(endpointPort, jwt, instruction);
                      assertEquals(201, result.statusCode(), result.body());
                      return System.nanoTime() - begin;
                    }));
          }
        }
        var latencies = new ArrayList<Long>();
        for (var sample : samples) latencies.add(sample.get());
        Collections.sort(latencies);
        double p95 = latencies.get(949) / 1_000_000.0;
        double seconds = (System.nanoTime() - loadStart) / 1_000_000_000.0;
        var report =
            Map.of(
                "requests",
                1000,
                "scheduledRequestsPerSecond",
                100,
                "elapsedSeconds",
                seconds,
                "p95Milliseconds",
                p95,
                "targetP95Milliseconds",
                500,
                "java",
                System.getProperty("java.version"),
                "availableProcessors",
                Runtime.getRuntime().availableProcessors(),
                "os",
                System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        Files.writeString(
            Path.of("target/performance.json"),
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertTrue(p95 < 500, "Reference p95 target exceeded: " + p95 + "ms");
        try (var c =
                DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var s = c.createStatement();
            var r =
                s.executeQuery(
                    "SELECT available_balance,(SELECT count(*) FROM ledger_entries) FROM accounts"
                        + " WHERE id='A'")) {
          r.next();
          assertEquals(new java.math.BigDecimal("373.250"), r.getBigDecimal(1));
          assertEquals(2002, r.getInt(2));
        }
      } finally {
        stop();
        Files.deleteIfExists(key);
      }
    }
  }
}
