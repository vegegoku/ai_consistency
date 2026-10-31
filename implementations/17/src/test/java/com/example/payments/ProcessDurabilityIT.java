package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ProcessDurabilityIT {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void actualProcessRestartPreservesCommittedReceiptAndBalances() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var keys = generator.generateKeyPair();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    String jwks =
        "{\"keys\":["
            + new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .keyID("test")
                .build()
                .toJSONString()
            + "]}";
    server.createContext(
        "/.well-known/openid-configuration",
        e -> {
          byte[] body =
              ("{\"issuer\":\""
                      + issuer
                      + "\",\"jwks_uri\":\""
                      + issuer
                      + "/jwks\",\"authorization_endpoint\":\""
                      + issuer
                      + "/authorize\",\"token_endpoint\":\""
                      + issuer
                      + "/token\",\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                  .getBytes();
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.createContext(
        "/jwks",
        e -> {
          byte[] body = jwks.getBytes();
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, body.length);
          e.getResponseBody().write(body);
          e.close();
        });
    server.start();
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    String base = "http://127.0.0.1:" + port;
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
            new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(issuer)
                .audience("payments")
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", "payments:write payments:read accounts:read")
                .build());
    jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
    String token = jwt.serialize();
    Process process = null;
    try {
      process = start(port, issuer, "first");
      await(base, process);
      try (var c =
              DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
          var st = c.createStatement()) {
        st.execute(
            "INSERT INTO customers(id,display_name,status)"
                + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
        st.execute(
            "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
                + " VALUES('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
      }
      String id = UUID.randomUUID().toString();
      String body =
          "{\"paymentId\":\""
              + id
              + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
              + Instant.now()
              + "\",\"purpose\":\"restart verification\"}";
      var request =
          HttpRequest.newBuilder(URI.create(base + "/api/v1/payments"))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var first = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
      process.destroyForcibly().waitFor();
      process = start(port, issuer, "restart");
      await(base, process);
      var replay = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
      assertThat(new ObjectMapper().readTree(replay.body()))
          .isEqualTo(new ObjectMapper().readTree(first.body()));
      var balance =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/api/v1/accounts/A/balance"))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(balance.statusCode()).isEqualTo(200);
      assertThat(new ObjectMapper().readTree(balance.body()).get("balance").asText())
          .isEqualTo("374.250");
      try (var c =
              DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
          var st = c.createStatement();
          var rs = st.executeQuery("SELECT count(*),sum(signed_amount) FROM ledger_entries")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(2);
        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("0");
      }
      measureLoad(base, token);
    } finally {
      if (process != null) process.destroyForcibly().waitFor();
      server.stop(0);
    }
  }

  Process start(int port, String issuer, String name) throws IOException {
    var b =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            "target/payment-service-1.0.0.jar",
            "--server.port=" + port,
            "--payments.require-tls=false",
            "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(),
            "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer);
    b.environment().put("DB_PASSWORD", DB.getPassword());
    return b.redirectErrorStream(true)
        .redirectOutput(new File("target/process-" + name + ".log"))
        .start();
  }

  void await(String base, Process process) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < end) {
      assertThat(process.isAlive())
          .withFailMessage("Service exited; inspect target/process-*.log")
          .isTrue();
      try {
        if (http.send(
                    HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding())
                .statusCode()
            == 200) return;
      } catch (IOException ignored) {
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Service readiness timeout");
  }

  void measureLoad(String base, String token) throws Exception {
    int count = 500;
    long started = System.nanoTime();
    var durations = new java.util.concurrent.ConcurrentLinkedQueue<Double>();
    var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
    try (var pool = java.util.concurrent.Executors.newScheduledThreadPool(32)) {
      for (int i = 0; i < count; i++) {
        long planned = started + i * 10_000_000L;
        futures.add(
            pool.schedule(
                () -> {
                  String payload =
                      "{\"paymentId\":\""
                          + UUID.randomUUID()
                          + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"0.001\",\"date\":\""
                          + Instant.now()
                          + "\",\"purpose\":\"benchmark\"}";
                  var request =
                      HttpRequest.newBuilder(URI.create(base + "/api/v1/payments"))
                          .timeout(Duration.ofSeconds(10))
                          .header("Authorization", "Bearer " + token)
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(payload))
                          .build();
                  var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                  durations.add((System.nanoTime() - planned) / 1_000_000d);
                  return response.statusCode();
                },
                Math.max(0, planned - System.nanoTime()),
                java.util.concurrent.TimeUnit.NANOSECONDS));
      }
      for (var future : futures)
        assertThat(future.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
    }
    var sorted = durations.stream().sorted().toList();
    double p95 = sorted.get((int) Math.ceil(count * .95) - 1);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(
            new File("target/load-report.json"),
            Map.of(
                "requests",
                count,
                "offered_rps",
                100,
                "duration_seconds",
                5,
                "p95_ms",
                p95,
                "max_ms",
                sorted.getLast(),
                "latency_includes_scheduling_delay",
                true,
                "environment",
                System.getProperty("os.name") + " / Java " + System.getProperty("java.version"),
                "timestamp",
                Instant.now().toString()));
    assertThat(p95).isLessThan(500);
  }
}
