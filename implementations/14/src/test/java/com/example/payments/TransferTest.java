package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.Codec;
import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.observability.Telemetry;
import com.example.payments.persistence.Database;
import com.example.payments.security.Caller;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TransferTest {
  @Container
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  static Database db;
  static Codec codec;
  static Config config;
  static Telemetry telemetry;
  PaymentService service;
  static final Caller ALICE =
      new Caller("alice", Set.of("payments:submit", "payments:read", "payments:admin"));

  @BeforeAll
  static void start() {
    config =
        new Config(
            PG.getJdbcUrl(),
            PG.getUsername(),
            PG.getPassword(),
            0,
            true,
            "https://test",
            "payments",
            "",
            65536,
            "10000.000",
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            1000,
            "",
            "",
            2555);
    db = new Database(config, true);
    codec =
        new Codec(
            65536, "10000.000", Duration.ofDays(30), Duration.ofMinutes(5), Clock.systemUTC());
    telemetry = new Telemetry();
  }

  @BeforeEach
  void seed() throws Exception {
    try (var c = db.pool.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE"
              + " payment_issues,ledger_entries,payments,allowed_destinations,account_debit_grants,accounts,customers"
              + " RESTART IDENTITY CASCADE");
      s.execute(
          "INSERT INTO customers(id,display_name,status)"
              + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      s.execute(
          "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
              + " VALUES('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE'),('C','bob','JOD',0,0,'FROZEN')");
    }
    service = new PaymentService(db, codec, telemetry);
  }

  @AfterAll
  static void stop() {
    if (db != null) db.close();
    if (telemetry != null) telemetry.close();
  }

  PaymentCommand command(String id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "invoice", "");
  }

  PaymentCommand command(String amount) {
    return command(UUID.randomUUID().toString(), amount);
  }

  PaymentService.Outcome transfer(PaymentCommand c) {
    return service.transfer(c, ALICE, UUID.randomUUID().toString());
  }

  BigDecimal balance(String id) throws Exception {
    try (var c = db.pool.getConnection();
        var p = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      p.setString(1, id);
      try (var r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  long count(String table) throws Exception {
    try (var c = db.pool.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void successReplayConflictAndRestart() throws Exception {
    var c = command("125.750");
    var first = transfer(c);
    assertEquals(201, first.http());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, count("ledger_entries"));
    assertEquals(first.body(), transfer(c).body());
    assertEquals(200, transfer(c).http());
    service = new PaymentService(db, codec, telemetry);
    assertEquals(first.body(), transfer(c).body());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> transfer(command(c.paymentId(), "1.000"))).code);
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void rejectionIsDurableAndUnauthorizedInvisible() throws Exception {
    var c = command("600");
    var result = transfer(c);
    assertEquals(409, result.http());
    assertEquals("INSUFFICIENT_FUNDS", result.body().get("code"));
    assertEquals(result.body(), transfer(c).body());
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(
        404,
        assertThrows(
                ApiException.class,
                () ->
                    service.payment(c.paymentId(), new Caller("mallory", Set.of("payments:read"))))
            .http);
  }

  @Test
  void duplicatesHaveExactlyOneFinancialEffect() throws Exception {
    var c = command("10");
    try (var executor = Executors.newFixedThreadPool(12)) {
      var jobs = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> transfer(c).http());
      var results = executor.invokeAll(jobs);
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return f.get() == 201;
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
    assertEquals(new BigDecimal("490.000"), balance("A"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentDebitsNeverOverdraw() throws Exception {
    try (var executor = Executors.newFixedThreadPool(12)) {
      var jobs = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 12; i++) jobs.add(() -> transfer(command("100")).http());
      var results = executor.invokeAll(jobs);
      int success = 0;
      for (var r : results) if (r.get() == 201) success++;
      assertEquals(5, success);
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void failureAfterDebitRollsBackEverything() throws Exception {
    service =
        new PaymentService(
            db,
            codec,
            telemetry,
            c -> {
              throw new SQLException("injected", "XX000");
            });
    assertThrows(ApiException.class, () -> transfer(command("20")));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void rejectedFrozenAndForbiddenWithoutDisclosure() throws Exception {
    var c = command("1");
    var frozen =
        new PaymentCommand(c.paymentId(), "A", "C", c.amount(), c.date(), c.purpose(), c.notes());
    assertEquals("ACCOUNT_NOT_ACTIVE", transfer(frozen).body().get("code"));
    var denied =
        service.transfer(
            command("1"),
            new Caller("mallory", Set.of("payments:submit")),
            UUID.randomUUID().toString());
    assertEquals(403, denied.http());
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void reconciliationDetectsCorruptionAndRecordsImmutable() throws Exception {
    transfer(command("1"));
    try (var c = db.pool.getConnection();
        var s = c.createStatement()) {
      assertThrows(
          SQLException.class, () -> s.execute("UPDATE ledger_entries SET signed_amount=1"));
      assertThrows(SQLException.class, () -> s.execute("DELETE FROM payments"));
      s.execute("UPDATE accounts SET available_balance=400 WHERE id='A'");
    }
    assertFalse(service.reconcile().isEmpty());
  }

  @Test
  void invalidNewTimeButOldReplayAllowed() throws Exception {
    var old =
        new PaymentCommand(
            UUID.randomUUID().toString(),
            "A",
            "B",
            new BigDecimal("1.000"),
            Instant.now().minus(Duration.ofDays(31)),
            "old",
            "");
    assertEquals("VALIDATION_FAILED", assertThrows(ApiException.class, () -> transfer(old)).code);
    assertEquals(0, count("payments"));
  }

  @Test
  void httpJsonXmlAuthenticationAndNegotiation() throws Exception {
    try (var api = httpServer(db, service)) {
      String token = jwt("alice", "payments:submit payments:read payments:admin");
      String id = UUID.randomUUID().toString(), date = Instant.now().toString();
      String body =
          "{\"paymentId\":\""
              + id
              + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
              + date
              + "\",\"purpose\":\"invoice\"}";
      var first = request(api, "POST", "/api/v1/payments", body, "application/json", null, token);
      assertEquals(201, first.statusCode());
      assertEquals("/api/v1/payments/" + id, first.headers().firstValue("Location").orElseThrow());
      assertTrue(first.headers().firstValue("X-Correlation-ID").isPresent());
      assertEquals("125.750", Codec.JSON.readTree(first.body()).get("amount").asText());
      String xml =
          "<payment><paymentId>"
              + id
              + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>"
              + date
              + "</date><purpose> invoice </purpose></payment>";
      var replay =
          request(
              api, "POST", "/api/v1/payments", xml, "application/xml", "application/xml", token);
      assertEquals(200, replay.statusCode());
      assertTrue(replay.body().contains("<amount>125.750</amount>"));
      assertEquals(
          201,
          request(
                  api,
                  "POST",
                  "/api/v1/payments",
                  xml.replace(id, UUID.randomUUID().toString()).replace("125.75", "1.000"),
                  "application/xml",
                  null,
                  token)
              .statusCode());
      assertEquals(new BigDecimal("373.250"), balance("A"));
      assertEquals(new BigDecimal("226.750"), balance("B"));
      assertEquals(
          200, request(api, "GET", "/api/v1/payments/" + id, null, null, null, token).statusCode());
      assertEquals(
          404,
          request(api, "GET", "/api/v1/accounts/B/balance", null, null, null, token).statusCode());
      assertEquals(
          401,
          request(api, "POST", "/api/v1/payments", body, "application/json", null, null)
              .statusCode());
      assertEquals(
          403,
          request(
                  api,
                  "POST",
                  "/api/v1/payments",
                  body,
                  "application/json",
                  null,
                  jwt("alice", "payments:read"))
              .statusCode());
      assertEquals(
          415,
          request(api, "POST", "/api/v1/payments", body, "text/plain", null, token).statusCode());
      assertEquals(
          406,
          request(api, "POST", "/api/v1/payments", body, "application/json", "text/plain", token)
              .statusCode());
      assertEquals(
          400,
          request(api, "POST", "/api/v1/payments", "{", "application/json", null, token)
              .statusCode());
      assertEquals(
          400,
          request(
                  api,
                  "POST",
                  "/api/v1/payments",
                  "<!DOCTYPE payment [<!ENTITY evil SYSTEM"
                      + " 'http://localhost:9/secret'>]><payment><notes>&evil;</notes></payment>",
                  "application/xml",
                  null,
                  token)
              .statusCode());
      assertTrue(service.reconcile().isEmpty());
    }
  }

  @Test
  void databaseUnavailableReadinessAndPaymentAre503() throws Exception {
    var unavailable = new Database(config, false);
    var isolated = new PaymentService(unavailable, codec, telemetry);
    try (var api = httpServer(unavailable, isolated)) {
      unavailable.close();
      assertEquals(503, request(api, "GET", "/health/ready", null, null, null, null).statusCode());
      assertEquals(200, request(api, "GET", "/health/live", null, null, null, null).statusCode());
      assertEquals(
          503,
          assertThrows(
                  ApiException.class,
                  () -> isolated.transfer(command("1"), ALICE, UUID.randomUUID().toString()))
              .http);
    }
  }

  @Test
  void persistedReceiptSurvivesDatabasePoolRecreation() throws Exception {
    var c = command("10");
    var original = transfer(c);
    try (var fresh = new Database(config, false)) {
      var restarted = new PaymentService(fresh, codec, telemetry);
      assertEquals(original.body(), restarted.payment(c.paymentId(), ALICE));
      assertEquals(200, restarted.transfer(c, ALICE, UUID.randomUUID().toString()).http());
      assertEquals("490.000", restarted.balance("A", ALICE).get("balance"));
      assertTrue(restarted.reconcile().isEmpty());
    }
  }

  @Test
  void boundedLockWaitDoesNotCommit() throws Exception {
    var cmd = command("1");
    try (var lock = db.pool.getConnection()) {
      lock.setAutoCommit(false);
      try (var s = lock.createStatement()) {
        s.execute("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
      }
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> assertEquals(503, assertThrows(ApiException.class, () -> transfer(cmd)).http));
      lock.rollback();
    }
    assertEquals(0, count("payments"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  private static com.nimbusds.jose.jwk.RSAKey httpKey;

  private com.example.payments.api.ApiServer httpServer(Database database, PaymentService payments)
      throws Exception {
    if (httpKey == null)
      httpKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("http-test").generate();
    var file = java.nio.file.Files.createTempFile("payment14-public-", ".json");
    java.nio.file.Files.writeString(
        file, new com.nimbusds.jose.jwk.JWKSet(httpKey.toPublicJWK()).toString());
    file.toFile().deleteOnExit();
    var auth =
        new com.example.payments.security.Authenticator(
            file.toString(), "https://test", "payments", Clock.systemUTC());
    var api =
        new com.example.payments.api.ApiServer(config, codec, payments, database, auth, telemetry);
    api.start();
    return api;
  }

  private String jwt(String subject, String scopes) throws Exception {
    var token =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID("http-test")
                .build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer("https://test")
                .audience("payments")
                .subject(subject)
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(600)))
                .claim("scope", scopes)
                .build());
    token.sign(new com.nimbusds.jose.crypto.RSASSASigner(httpKey));
    return token.serialize();
  }

  private java.net.http.HttpResponse<String> request(
      com.example.payments.api.ApiServer api,
      String method,
      String path,
      String body,
      String type,
      String accept,
      String token)
      throws Exception {
    var b =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + api.port() + path))
            .timeout(Duration.ofSeconds(15));
    if (type != null) b.header("Content-Type", type);
    if (accept != null) b.header("Accept", accept);
    if (token != null) b.header("Authorization", "Bearer " + token);
    b.method(
        method,
        body == null
            ? java.net.http.HttpRequest.BodyPublishers.noBody()
            : java.net.http.HttpRequest.BodyPublishers.ofString(body));
    return java.net.http.HttpClient.newHttpClient()
        .send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void explicitDebitGrantDestinationPolicyAndDailyLimit() throws Exception {
    try (var c = db.pool.getConnection();
        var st = c.createStatement()) {
      st.execute("INSERT INTO account_debit_grants(account_id,principal) VALUES('A','delegate')");
      st.execute("UPDATE accounts SET unrestricted_destinations=false WHERE id='A'");
    }
    Caller delegate = new Caller("delegate", Set.of("payments:submit", "payments:read"));
    assertEquals(
        "FORBIDDEN",
        service.transfer(command("1"), delegate, UUID.randomUUID().toString()).body().get("code"));
    try (var c = db.pool.getConnection();
        var st = c.createStatement()) {
      st.execute("INSERT INTO allowed_destinations VALUES('A','B')");
      st.execute("UPDATE accounts SET daily_debit_limit=1 WHERE id='A'");
    }
    assertEquals(
        201, service.transfer(command("1"), delegate, UUID.randomUUID().toString()).http());
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED",
        service
            .transfer(command("0.001"), delegate, UUID.randomUUID().toString())
            .body()
            .get("code"));
    assertEquals(new BigDecimal("499.000"), balance("A"));
  }

  @Test
  void replayRemainsValidAfterTimestampWindow() throws Exception {
    var cmd = command("1");
    var original = transfer(cmd);
    var aged =
        new Codec(
            65536,
            "10000.000",
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            Clock.offset(Clock.systemUTC(), Duration.ofDays(31)));
    var restarted = new PaymentService(db, aged, telemetry);
    assertEquals(
        original.body(), restarted.transfer(cmd, ALICE, UUID.randomUUID().toString()).body());
    assertEquals(new BigDecimal("499.000"), balance("A"));
  }

  @Test
  void oppositeDirectionTransfersLockInOneOrder() throws Exception {
    Caller bob = new Caller("bob", Set.of("payments:submit"));
    try (var executor = Executors.newFixedThreadPool(12)) {
      var jobs = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 20; i++) {
        final boolean reverse = i % 2 == 0;
        jobs.add(
            () -> {
              var cmd = command("1");
              if (reverse)
                cmd =
                    new PaymentCommand(
                        cmd.paymentId(),
                        "B",
                        "A",
                        cmd.amount(),
                        cmd.date(),
                        cmd.purpose(),
                        cmd.notes());
              return service
                  .transfer(cmd, reverse ? bob : ALICE, UUID.randomUUID().toString())
                  .http();
            });
      }
      for (var f : executor.invokeAll(jobs)) assertEquals(201, f.get());
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void missingAccountRejectionIsNonEnumerable() throws Exception {
    var c = command("1");
    var missing =
        new PaymentCommand(
            c.paymentId(), "MISSING", "B", c.amount(), c.date(), c.purpose(), c.notes());
    assertEquals(403, transfer(missing).http());
    var ex =
        assertThrows(
            ApiException.class,
            () -> service.payment(c.paymentId(), new Caller("bob", Set.of("payments:read"))));
    assertEquals(404, ex.http);
  }

  @Test
  void lostReceiptBodyAfterCommitReplaysExactlyOnce() throws Exception {
    try (var api = httpServer(db, service)) {
      String token = jwt("alice", "payments:submit payments:read");
      var cmd = command("1");
      String body =
          Codec.JSON.writeValueAsString(
              Map.of(
                  "paymentId",
                  cmd.paymentId(),
                  "fromAccount",
                  "A",
                  "toAccount",
                  "B",
                  "amount",
                  "1.000",
                  "date",
                  cmd.date().toString(),
                  "purpose",
                  "invoice"));
      byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try (var socket = new java.net.Socket("localhost", api.port())) {
        socket.setSoTimeout(5000);
        var out = socket.getOutputStream();
        out.write(
            ("POST /api/v1/payments HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                    + token
                    + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
        var header = new StringBuilder();
        var in = socket.getInputStream();
        while (!header.toString().endsWith("\r\n\r\n")) {
          int next = in.read();
          assertNotEquals(-1, next);
          header.append((char) next);
        }
        assertTrue(header.toString().startsWith("HTTP/1.1 201"));
        // Simulate losing the committed receipt body; the client has no receipt to retain.
      }
      var replay = request(api, "POST", "/api/v1/payments", body, "application/json", null, token);
      assertEquals(200, replay.statusCode());
      assertEquals("COMPLETED", Codec.JSON.readTree(replay.body()).get("status").asText());
      assertEquals(new BigDecimal("499.000"), balance("A"));
      assertEquals(new BigDecimal("101.000"), balance("B"));
      assertEquals(2, count("ledger_entries"));
    }
  }

  @Test
  void authenticationErrorsHonorXmlNegotiation() throws Exception {
    try (var api = httpServer(db, service)) {
      var denied =
          request(
              api, "POST", "/api/v1/payments", "{}", "application/json", "application/xml", null);
      assertEquals(401, denied.statusCode());
      assertTrue(
          denied.headers().firstValue("Content-Type").orElseThrow().startsWith("application/xml"));
      assertTrue(denied.body().contains("<code>UNAUTHENTICATED</code>"));
    }
  }
}
