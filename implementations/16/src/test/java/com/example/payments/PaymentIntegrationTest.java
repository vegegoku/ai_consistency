package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PaymentIntegrationTest {
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
  static final com.nimbusds.jose.jwk.RSAKey KEY;
  static final com.sun.net.httpserver.HttpServer JWKS;

  static {
    try {
      KEY = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
      JWKS =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      JWKS.createContext(
          "/jwks",
          exchange -> {
            byte[] b =
                new com.nimbusds.jose.jwk.JWKSet(KEY.toPublicJWK())
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
          });
      JWKS.start();
      DB.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @org.springframework.boot.test.web.server.LocalServerPort int port;

  String token(String issuer, String audience, Instant expires) throws Exception {
    var claims =
        new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject("alice")
            .issuer(issuer)
            .audience(audience)
            .expirationTime(java.util.Date.from(expires))
            .issueTime(java.util.Date.from(Instant.now().minusSeconds(5)))
            .claim("scope", "payments:write payments:read accounts:read")
            .claim("customer_id", "C1")
            .build();
    var jwt =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(KEY.getKeyID())
                .build(),
            claims);
    jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY));
    return jwt.serialize();
  }

  @AfterAll
  static void stopJwks() {
    JWKS.stop(0);
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("payments.require-tls", () -> false);
    r.add(
        "payments.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    r.add("payments.rate-per-minute", () -> 10000);
  }

  @Autowired PaymentService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ReconciliationService reconciliation;
  final Caller caller =
      new Caller("alice", "C1", Set.of("payments:write", "payments:read", "accounts:read"));

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE ledger_entries,payments,account_debit_grants,accounts,customers CASCADE");
    jdbc.update(
        "INSERT INTO customers(id,display_name,status)"
            + " VALUES('C1','Alice','ACTIVE'),('C2','Bob','ACTIVE')");
    jdbc.update(
        "INSERT INTO accounts(id,customer_id,available_balance,opening_balance)"
            + " VALUES('A','C1',500,500),('B','C2',100,100)");
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "invoice", null);
  }

  BigDecimal balance(String id) {
    return jdbc.queryForObject(
        "SELECT available_balance FROM accounts WHERE id=?", BigDecimal.class, id);
  }

  @Test
  void transferReplayConflictAndReconcile() {
    var c = command(UUID.randomUUID(), "125.750");
    assertEquals(201, service.transfer(c, caller, "cid").http());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    assertEquals(200, service.transfer(c, caller, "next").http());
    assertEquals(
        409,
        service
            .transfer(
                new PaymentCommand(
                    c.paymentId(), "A", "B", new BigDecimal("1.000"), c.date(), c.purpose(), null),
                caller,
                "next")
            .http());
    assertTrue(reconciliation.report().isEmpty());
    jdbc.update("UPDATE accounts SET available_balance=available_balance+1 WHERE id='A'");
    assertFalse(reconciliation.report().isEmpty());
  }

  @Test
  void durableBusinessRejection() {
    var c = command(UUID.randomUUID(), "600");
    var first = service.transfer(c, caller, "x");
    assertEquals(409, first.http());
    assertEquals("INSUFFICIENT_FUNDS", first.body().get("code"));
    jdbc.update("UPDATE accounts SET available_balance=1000 WHERE id='A'");
    assertEquals(first.body(), service.transfer(c, caller, "new").body());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
  }

  @Test
  void concurrentDuplicatesAndNoOverdraft() throws Exception {
    var c = command(UUID.randomUUID(), "400");
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<PaymentOutcome>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> service.transfer(c, caller, "race")));
      for (var job : jobs) assertTrue(Set.of(200, 201).contains(job.get().http()));
    }
    assertEquals(new BigDecimal("100.000"), balance("A"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    try (var pool = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Future<PaymentOutcome>>();
      for (int i = 0; i < 8; i++)
        jobs.add(
            pool.submit(() -> service.transfer(command(UUID.randomUUID(), "30"), caller, "race")));
      for (var job : jobs) job.get();
    }
    assertEquals(new BigDecimal("10.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("A").add(balance("B")));
  }

  @Test
  void unauthorizedAndFrozen() {
    assertEquals(
        403,
        service
            .transfer(
                command(UUID.randomUUID(), "1"), new Caller("mallory", "C2", caller.scopes()), "x")
            .http());
    jdbc.update("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    assertEquals(
        "ACCOUNT_NOT_ACTIVE",
        service.transfer(command(UUID.randomUUID(), "1"), caller, "x").body().get("code"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void injectedDebitFailureRollsBack() {
    var failing =
        new PaymentService(
            jdbc,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            () -> {
              throw new IllegalStateException("injected");
            },
            3,
            2000,
            5000);
    assertThrows(
        IllegalStateException.class,
        () -> failing.transfer(command(UUID.randomUUID(), "1"), caller, "x"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class));
  }

  String body(UUID id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"125.750\",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"invoice\"}";
  }

  org.springframework.test.web.servlet.request.RequestPostProcessor auth() {
    return jwt()
        .jwt(j -> j.subject("alice").claim("customer_id", "C1"))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_payments:write"),
            new SimpleGrantedAuthority("SCOPE_payments:read"),
            new SimpleGrantedAuthority("SCOPE_accounts:read"));
  }

  @Test
  void jsonApiSecurityAndNegotiation() throws Exception {
    UUID id = UUID.randomUUID();
    mvc.perform(post("/api/v1/payments").contentType("application/json").content(body(id)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(
            post("/api/v1/payments").with(auth()).contentType("application/json").content(body(id)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value("125.750"))
        .andExpect(header().exists("X-Correlation-ID"));
    mvc.perform(get("/api/v1/payments/" + id).with(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    mvc.perform(get("/api/v1/accounts/A/balance").with(auth()))
        .andExpect(jsonPath("$.balance").value("374.250"));
    mvc.perform(post("/api/v1/payments").with(auth()).contentType("text/plain").content("x"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/payments")
                .with(auth())
                .contentType("application/json")
                .accept("text/csv")
                .content(body(UUID.randomUUID())))
        .andExpect(status().isNotAcceptable());
  }

  @Test
  void xmlAndCrossFormatReplay() throws Exception {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    mvc.perform(
            post("/api/v1/payments")
                .with(auth())
                .contentType("application/xml")
                .accept("application/xml")
                .content(xml))
        .andExpect(status().isCreated())
        .andExpect(xpath("/response/amount").string("125.750"));
    String json =
        body(id).replaceFirst("\\\"date\\\":\\\"[^\\\"]+\\\"", "\"date\":\"" + date + "\"");
    mvc.perform(post("/api/v1/payments").with(auth()).contentType("application/json").content(json))
        .andExpect(status().isOk());
    assertEquals(new BigDecimal("374.250"), balance("A"));
  }

  @Test
  void ledgerImmutableAndFreshServiceSeesCommit() {
    var c = command(UUID.randomUUID(), "1");
    service.transfer(c, caller, "lost-response");
    var restarted =
        new PaymentService(
            jdbc,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            () -> {},
            3,
            2000,
            5000);
    assertEquals(200, restarted.transfer(c, caller, "retry").http());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE ledger_entries SET signed_amount=1"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("UPDATE payments SET purpose='changed'"));
  }

  @Test
  void actualBearerSignatureIssuerAudienceExpiryAndScopes() throws Exception {
    String valid =
        token(
            "http://localhost:8081/realms/payments",
            "payment-service",
            Instant.now().plusSeconds(600));
    mvc.perform(get("/api/v1/accounts/A/balance").header("Authorization", "Bearer " + valid))
        .andExpect(status().isOk());
    for (String bad :
        new String[] {
          token("wrong", "payment-service", Instant.now().plusSeconds(600)),
          token("http://localhost:8081/realms/payments", "wrong", Instant.now().plusSeconds(600)),
          token(
              "http://localhost:8081/realms/payments",
              "payment-service",
              Instant.now().minusSeconds(600)),
          valid.substring(0, valid.length() - 8) + "abcdefgh"
        })
      mvc.perform(get("/api/v1/accounts/A/balance").header("Authorization", "Bearer " + bad))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice").claim("customer_id", "C1"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:read")))
                .contentType("application/json")
                .content(body(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  void inaccessibleLookupsAndMalformedDocumentsAreSafe() throws Exception {
    var c = command(UUID.randomUUID(), "1");
    service.transfer(c, caller, "x");
    mvc.perform(
            get("/api/v1/payments/" + c.paymentId())
                .with(
                    jwt()
                        .jwt(j -> j.subject("other").claim("customer_id", "unrelated"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/accounts/B/balance").with(auth())).andExpect(status().isNotFound());
    for (String content : new String[] {"{", "{\"amount\":1,\"amount\":2}"})
      mvc.perform(
              post("/api/v1/payments")
                  .with(auth())
                  .contentType("application/json")
                  .content(content))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MALFORMED_MESSAGE"));
    mvc.perform(
            post("/api/v1/payments")
                .with(auth())
                .contentType("application/xml")
                .content(
                    "<!DOCTYPE payment [<!ENTITY e SYSTEM"
                        + " 'http://127.0.0.1:1/evil'>]><payment><purpose>&e;</purpose></payment>"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void boundedLockTimeoutRollsBackRegistration() throws Exception {
    try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      connection.setAutoCommit(false);
      try (var stmt = connection.createStatement()) {
        stmt.executeQuery("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
      }
      var constrained =
          new PaymentService(
              jdbc,
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                  jdbc.getDataSource()),
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
              () -> {},
              1,
              100,
              500);
      var e =
          assertThrows(
              ApiFailure.class,
              () -> constrained.transfer(command(UUID.randomUUID(), "1"), caller, "lock"));
      assertEquals(503, e.http());
      connection.rollback();
    }
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class));
  }

  @Test
  void unavailableDatabaseIsTransient() {
    var ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/missing?connectTimeout=1", "missing", "missing");
    var unavailable =
        new PaymentService(
            new JdbcTemplate(ds),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            () -> {},
            1,
            100,
            500);
    assertEquals(
        503,
        assertThrows(
                ApiFailure.class,
                () -> unavailable.transfer(command(UUID.randomUUID(), "1"), caller, "offline"))
            .http());
  }

  @Test
  void oppositeDirectionLocksAndAccountRestrictions() throws Exception {
    jdbc.update("INSERT INTO account_debit_grants(account_id,subject) VALUES('B','alice')");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(() -> service.transfer(command(UUID.randomUUID(), "20"), caller, "a"));
      var b =
          executor.submit(
              () ->
                  service.transfer(
                      new PaymentCommand(
                          UUID.randomUUID(),
                          "B",
                          "A",
                          new BigDecimal("20.000"),
                          Instant.now(),
                          "back",
                          null),
                      caller,
                      "b"));
      assertEquals(201, a.get().http());
      assertEquals(201, b.get().http());
    }
    assertEquals(new BigDecimal("500.000"), balance("A"));
    jdbc.update("UPDATE accounts SET max_transfer=10 WHERE id='A'");
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED",
        service.transfer(command(UUID.randomUUID(), "11"), caller, "limit").body().get("code"));
    jdbc.update("UPDATE accounts SET can_receive=false WHERE id='B'");
    assertEquals(403, service.transfer(command(UUID.randomUUID(), "1"), caller, "receive").http());
  }

  @Test
  void secondApplicationStartupFindsOriginalOutcome() {
    var c = command(UUID.randomUUID(), "1");
    service.transfer(c, caller, "commit");
    try (var fresh =
        new org.springframework.boot.builder.SpringApplicationBuilder(PaymentApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + DB.getJdbcUrl(),
                "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(),
                "--payments.require-tls=false",
                "--payments.jwt.jwk-set-uri=http://127.0.0.1:"
                    + JWKS.getAddress().getPort()
                    + "/jwks",
                "--management.tracing.enabled=false")) {
      var recovered = fresh.getBean(PaymentService.class);
      assertEquals("COMPLETED", recovered.get(c.paymentId(), caller).body().get("status"));
      assertEquals(200, recovered.transfer(c, caller, "lost-response").http());
      assertEquals(new BigDecimal("499.000"), balance("A"));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "payments.load",
      matches = "true")
  void referenceHttpLoadAt100RequestsPerSecond() throws Exception {
    String bearer =
        token(
            "http://localhost:8081/realms/payments",
            "payment-service",
            Instant.now().plusSeconds(600));
    var client =
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();
    var durations = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
    try (var executor = Executors.newFixedThreadPool(32)) {
      List<Future<Integer>> futures = new ArrayList<>();
      long origin = System.nanoTime();
      for (int i = 0; i < 1000; i++) {
        long wait = origin + i * 10_000_000L - System.nanoTime();
        if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        futures.add(
            executor.submit(
                () -> {
                  String content = body(UUID.randomUUID()).replace("125.750", "0.001");
                  var request =
                      java.net.http.HttpRequest.newBuilder(
                              java.net.URI.create("http://localhost:" + port + "/api/v1/payments"))
                          .timeout(java.time.Duration.ofSeconds(10))
                          .header("Authorization", "Bearer " + bearer)
                          .header("Content-Type", "application/json")
                          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(content))
                          .build();
                  long start = System.nanoTime();
                  int status =
                      client
                          .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                          .statusCode();
                  durations.add(System.nanoTime() - start);
                  return status;
                }));
      }
      for (var f : futures) assertEquals(201, f.get());
    }
    var sorted = durations.stream().sorted().toList();
    double p95 = sorted.get(949) / 1_000_000.0;
    String result =
        "requests=1000 target_rate=100/s duration=10s p95_ms="
            + p95
            + " runtime="
            + Runtime.version();
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/load-result.txt"), result);
    System.out.println(result);
    assertTrue(p95 < 500, "Reference p95 exceeded target: " + result);
    assertTrue(reconciliation.report().isEmpty());
  }

  @Test
  void survivesRealJvmProcessRestart() throws Exception {
    int childPort;
    try (var socket = new java.net.ServerSocket(0)) {
      childPort = socket.getLocalPort();
    }
    UUID id = UUID.randomUUID();
    String requestBody = body(id).replace("125.750", "1.000");
    String bearer =
        token(
            "http://localhost:8081/realms/payments",
            "payment-service",
            Instant.now().plusSeconds(600));
    var client = java.net.http.HttpClient.newHttpClient();
    Process first = startChild(childPort, "target/restart-first.log");
    try {
      awaitReady(first, childPort, client);
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create("http://localhost:" + childPort + "/api/v1/payments"))
                  .header("Authorization", "Bearer " + bearer)
                  .header("Content-Type", "application/json")
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode(), response.body());
    } finally {
      stopChild(first);
    }
    Process second = startChild(childPort, "target/restart-second.log");
    try {
      awaitReady(second, childPort, client);
      var query =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create(
                          "http://localhost:" + childPort + "/api/v1/payments/" + id))
                  .header("Authorization", "Bearer " + bearer)
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, query.statusCode());
      assertTrue(query.body().contains("COMPLETED"));
      var replay =
          client.send(
              java.net.http.HttpRequest.newBuilder(
                      java.net.URI.create("http://localhost:" + childPort + "/api/v1/payments"))
                  .header("Authorization", "Bearer " + bearer)
                  .header("Content-Type", "application/json")
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, replay.statusCode());
      assertEquals(new BigDecimal("499.000"), balance("A"));
      assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    } finally {
      stopChild(second);
    }
  }

  Process startChild(int port, String logfile) throws java.io.IOException {
    return new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "com.example.payments.PaymentApplication",
            "--server.port=" + port,
            "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(),
            "--payments.require-tls=false",
            "--payments.jwt.jwk-set-uri=http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks",
            "--management.tracing.enabled=false",
            "--logging.level.root=WARN")
        .redirectErrorStream(true)
        .redirectOutput(new java.io.File(logfile))
        .start();
  }

  void awaitReady(Process process, int port, java.net.http.HttpClient client) throws Exception {
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < end) {
      assertTrue(process.isAlive(), "Child JVM exited; inspect target/restart-*.log");
      try {
        var response =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://localhost:" + port + "/actuator/health/readiness"))
                    .timeout(java.time.Duration.ofSeconds(1))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) return;
      } catch (java.io.IOException ignored) {
      }
      Thread.sleep(100);
    }
    fail("Child JVM readiness timed out");
  }

  void stopChild(Process process) throws InterruptedException {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void invalidAndClosedAccountsDoNotLeakOrMoveFunds() {
    jdbc.update("UPDATE accounts SET status='CLOSED' WHERE id='B'");
    assertEquals(
        "ACCOUNT_NOT_ACTIVE",
        service.transfer(command(UUID.randomUUID(), "1"), caller, "closed").body().get("code"));
    var missing =
        new PaymentCommand(
            UUID.randomUUID(),
            "A",
            "MISSING",
            new BigDecimal("1.000"),
            Instant.now(),
            "missing",
            null);
    assertEquals(403, service.transfer(missing, caller, "missing").http());
    var admin = new Caller("operator", null, Set.of("payments:write", "payments:admin"));
    assertEquals(
        404,
        service
            .transfer(
                new PaymentCommand(
                    UUID.randomUUID(),
                    "A",
                    "MISSING",
                    missing.amount(),
                    missing.date(),
                    missing.purpose(),
                    null),
                admin,
                "admin")
            .http());
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
  }

  @Test
  void reconciliationDetectsDeliberatelyCorruptedLedgerFixture() {
    service.transfer(command(UUID.randomUUID(), "1"), caller, "audit");
    jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER USER");
    try {
      jdbc.update("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");
    } finally {
      jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER USER");
    }
    assertTrue(
        reconciliation.report().stream()
            .anyMatch(i -> "PAYMENT_LEDGER_MISMATCH".equals(i.get("issue"))));
  }

  @Test
  void minimumRuntimePrivilegesCanTransferWithoutFinancialMutationRights() throws Exception {
    String grants =
        java.nio.file.Files.readString(java.nio.file.Path.of("scripts/runtime-grants.sql"))
            .replace("DATABASE payments", "DATABASE " + DB.getDatabaseName());
    jdbc.execute(grants);
    jdbc.execute("CREATE ROLE runtime16 LOGIN PASSWORD 'runtime-test-only'");
    jdbc.execute("GRANT payments_runtime TO runtime16");
    var runtimeDb =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DB.getJdbcUrl(), "runtime16", "runtime-test-only"));
    var runtimeService =
        new PaymentService(
            runtimeDb,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                runtimeDb.getDataSource()),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            () -> {},
            3,
            2000,
            5000);
    assertEquals(
        201,
        runtimeService.transfer(command(UUID.randomUUID(), "1"), caller, "least-privilege").http());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtimeDb.update("DELETE FROM ledger_entries"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtimeDb.update("UPDATE payments SET purpose='unauthorized'"));
    assertEquals(new BigDecimal("499.000"), balance("A"));
  }

  @Test
  void financialMetricsCountCommitsWithoutCountingReplays() {
    var meter = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var measured =
        new PaymentService(
            jdbc,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()),
            meter,
            () -> {},
            3,
            2000,
            5000);
    var command = command(UUID.randomUUID(), "1");
    measured.transfer(command, caller, "first");
    measured.transfer(command, caller, "replay");
    assertEquals(1.0, meter.get("payments.completed").counter().count());
    assertEquals(1.0, meter.get("payments.replays").counter().count());
  }
}
