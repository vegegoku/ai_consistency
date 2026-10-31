package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/** Exercises the packaged artifact through real JVM termination and restart. */
class DurabilityIT {
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private PostgreSQLContainer<?> pg;
  private Process app;
  private int port;

  @BeforeEach
  void setup() throws Exception {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
    try (var socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    start();
  }

  void start() throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dquarkus.profile=local",
                "-Dquarkus.http.port=" + port,
                "-Dquarkus.otel.sdk.disabled=true",
                "-Dquarkus.datasource.jdbc.url=" + pg.getJdbcUrl(),
                "-Dquarkus.datasource.username=" + pg.getUsername(),
                "-Dquarkus.datasource.password=" + pg.getPassword(),
                "-jar",
                "target/quarkus-app/quarkus-run.jar"));
    app =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(Path.of("target/durability-process.log").toFile()))
            .start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (!app.isAlive()) fail("Packaged service exited; see target/durability-process.log");
      try {
        if (request("GET", "/q/health/ready", null, null).statusCode() == 200) return;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    fail("Packaged service did not become ready");
  }

  void kill() throws Exception {
    if (app != null) {
      app.destroyForcibly();
      assertTrue(app.waitFor(10, TimeUnit.SECONDS));
      app = null;
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    kill();
    if (pg != null) pg.stop();
  }

  HttpResponse<String> request(String method, String path, String body, String token)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body != null) b.header("Content-Type", "application/json");
    return client.send(
        b.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void responseLossRestartAndDatabaseOutageAreSafe() throws Exception {
    UUID id = UUID.randomUUID();
    String body =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\""
            + Instant.now()
            + "\",\"purpose\":\"Durability\"}";
    String token =
        ApiTest.token(
            "alice", "payments:read payments:write", Instant.now().getEpochSecond() + 300);
    var original = request("POST", "/api/v1/payments", body, token);
    assertEquals(201, original.statusCode(), original.body());
    // The client discards the committed response and the process is killed before it retries.
    kill();
    start();
    var replay = request("POST", "/api/v1/payments", body, token);
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(json.readTree(original.body()), json.readTree(replay.body()));
    assertEquals(
        "374.250",
        json.readTree(request("GET", "/api/v1/accounts/ACC-1001/balance", null, token).body())
            .path("balance")
            .asText());
    assertEquals(200, request("GET", "/api/v1/payments/" + id, null, token).statusCode());
    pg.stop();
    pg = null;
    assertEquals(503, request("GET", "/q/health/ready", null, null).statusCode());
    var unavailable = request("POST", "/api/v1/payments", body, token);
    assertEquals(503, unavailable.statusCode());
    assertEquals(
        "TEMPORARILY_UNAVAILABLE", json.readTree(unavailable.body()).path("code").asText());
  }
}
