package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.api.MessageParser;
import com.example.payments.application.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@org.springframework.context.annotation.Import(PaymentIntegrationTest.TestConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIntegrationTest {
  @org.springframework.boot.test.context.TestConfiguration
  static class TestConfig {
    @org.springframework.context.annotation.Bean
    jakarta.servlet.Filter lostResponseFilter() {
      return (request, response, chain) -> {
        chain.doFilter(request, response);
        if ("true"
            .equals(
                ((jakarta.servlet.http.HttpServletRequest) request)
                    .getHeader("X-Test-Drop-Response")))
          throw new java.io.IOException("Simulated transport response loss after commit");
      };
    }

    @org.springframework.context.annotation.Bean
    FailureProbe probe() {
      return new FailureProbe();
    }
  }

  static {
    System.setProperty("api.version", "1.44");
  }

  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    DB.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("payment.require-tls", () -> false);
    r.add("payment.rate-per-minute", () -> 10000);
  }

  @Autowired PaymentService service;
  @Autowired MessageParser parser;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired FailureProbe probe;
  @MockBean JwtDecoder decoder;

  @BeforeEach
  void seed() {
    probe.clear();
    jdbc.execute(
        "TRUNCATE payment_issues,ledger_entries,payments,account_permissions,accounts,customers"
            + " RESTART IDENTITY CASCADE");
    jdbc.update(
        "insert into customers(id,display_name,status) values"
            + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
    jdbc.update(
        "insert into accounts(id,customer_id,available_balance,opening_balance) values"
            + " ('A','alice',500,500),('B','bob',100,100),('C','bob',100,100)");
  }

  String json(String id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"Invoice\"}";
  }

  BigDecimal balance(String id) {
    return jdbc.queryForObject(
        "select available_balance from accounts where id=?", BigDecimal.class, id);
  }

  @Test
  void successReplayConflictAndAudit() {
    String j = json(UUID.randomUUID().toString(), "125.750");
    var c = parser.parse(j.getBytes(), "application/json");
    assertEquals(201, service.transfer(c, "alice", "corr").http());
    assertEquals(200, service.transfer(c, "alice", "other").http());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, jdbc.queryForObject("select count(*) from ledger_entries", Integer.class));
    assertEquals("alice", jdbc.queryForObject("select requested_by from payments", String.class));
    assertEquals(
        409,
        service
            .transfer(
                parser.parse(j.replace("125.750", "1.000").getBytes(), "application/json"),
                "alice",
                "corr")
            .http());
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void rejectedReplayIsDurable() {
    var c = parser.parse(json(UUID.randomUUID().toString(), "600").getBytes(), "application/json");
    var first = service.transfer(c, "alice", "corr");
    assertEquals("INSUFFICIENT_FUNDS", first.body().get("code"));
    jdbc.update("update accounts set available_balance=1000 where id='A'");
    assertEquals(first.body(), service.transfer(c, "alice", "other").body());
    assertEquals(0, jdbc.queryForObject("select count(*) from ledger_entries", Integer.class));
  }

  @Test
  void rollbackAfterDebit() {
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    probe.failOnce();
    assertThrows(RuntimeException.class, () -> service.transfer(c, "alice", "corr"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, jdbc.queryForObject("select count(*) from payments", Integer.class));
    assertEquals(201, service.transfer(c, "alice", "corr").http());
  }

  @Test
  void duplicateRacesAndResponseLoss() throws Exception {
    var c =
        parser.parse(json(UUID.randomUUID().toString(), "125.750").getBytes(), "application/json");
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 16; i++) tasks.add(() -> service.transfer(c, "alice", "corr").http());
      var results = pool.invokeAll(tasks);
      int created = 0;
      for (var r : results) {
        int status = r.get();
        assertTrue(status == 200 || status == 201);
        if (status == 201) created++;
      }
      assertEquals(1, created);
    }
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(200, service.transfer(c, "alice", "corr").http());
  }

  @Test
  void competingPaymentsNeverOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        var c =
            parser.parse(json(UUID.randomUUID().toString(), "100").getBytes(), "application/json");
        tasks.add(() -> service.transfer(c, "alice", "corr").http());
      }
      int good = 0;
      for (var f : pool.invokeAll(tasks)) if (f.get() == 201) good++;
      assertEquals(5, good);
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(
        new BigDecimal("700.000"),
        jdbc.queryForObject("select sum(available_balance) from accounts", BigDecimal.class));
    assertTrue(service.reconcile().isEmpty());
  }

  @Test
  void authorizationAndFrozenAccounts() {
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    assertEquals(403, service.transfer(c, "mallory", "corr").http());
    var d = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    jdbc.update("update accounts set status='FROZEN' where id='B'");
    assertEquals("ACCOUNT_NOT_ACTIVE", service.transfer(d, "alice", "corr").body().get("code"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void reconciliationDetectsCorruption() {
    jdbc.update("update accounts set available_balance=499 where id='A'");
    assertFalse(service.reconcile().isEmpty());
  }

  @Test
  void databaseLedgerImmutability() {
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    service.transfer(c, "alice", "corr");
    assertThrows(Exception.class, () -> jdbc.update("update ledger_entries set signed_amount=0"));
    assertThrows(Exception.class, () -> jdbc.update("delete from payments"));
  }

  @Test
  void jsonHttpAndVisibility() throws Exception {
    String id = UUID.randomUUID().toString();
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(json(id, "125.750")))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/payments/" + id))
        .andExpect(jsonPath("$.amount").value("125.750"));
    mvc.perform(
            get("/api/v1/payments/" + id)
                .with(
                    jwt()
                        .jwt(j -> j.subject("mallory"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/accounts/A/balance")
                .with(
                    jwt()
                        .jwt(j -> j.subject("bob"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void xmlHttpNegotiationAndSecurity() throws Exception {
    String xml =
        "<payment><paymentId>"
            + UUID.randomUUID()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + Instant.now()
            + "</date><purpose>Invoice</purpose></payment>";
    var auth =
        jwt()
            .jwt(j -> j.subject("alice"))
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));
    mvc.perform(
            post("/api/v1/payments")
                .with(auth)
                .contentType("application/xml")
                .accept("application/xml")
                .content(xml))
        .andExpect(status().isCreated())
        .andExpect(xpath("/response/amount").string("125.750"));
    mvc.perform(post("/api/v1/payments").with(auth).contentType("text/plain").content("x"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/payments")
                .with(auth)
                .contentType("application/json")
                .accept("text/plain")
                .content("{}"))
        .andExpect(status().isNotAcceptable());
    mvc.perform(get("/api/v1/accounts/A/balance"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void missingAccountsRestrictionsAndScopes() throws Exception {
    String j = json(UUID.randomUUID().toString(), "1").replace("\"B\"", "\"UNKNOWN\"");
    assertEquals(
        403,
        service.transfer(parser.parse(j.getBytes(), "application/json"), "alice", "corr").http());
    jdbc.update("update accounts set transfer_limit=0.500 where id='A'");
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    assertEquals("PAYMENT_LIMIT_EXCEEDED", service.transfer(c, "alice", "corr").body().get("code"));
    mvc.perform(
            post("/api/v1/payments")
                .with(jwt().jwt(x -> x.subject("alice")))
                .contentType("application/json")
                .content(json(UUID.randomUUID().toString(), "1")))
        .andExpect(status().isForbidden());
  }

  @Test
  void locksHaveBoundedRetryAndDoNotPersistTransientFailure() throws Exception {
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    try (var connection =
        java.sql.DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
      connection.setAutoCommit(false);
      connection.createStatement().execute("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
      long start = System.nanoTime();
      var failure =
          assertThrows(
              com.example.payments.domain.ApiFailure.class,
              () -> service.transfer(c, "alice", "corr"));
      assertEquals(503, failure.http);
      assertTrue((System.nanoTime() - start) / 1e9 < 8);
      connection.rollback();
    }
    assertEquals(0, jdbc.queryForObject("select count(*) from payments", Integer.class));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(201, service.transfer(c, "alice", "corr").http());
  }

  @Test
  void restartDurability() {
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    service.transfer(c, "alice", "corr");
    String[] args = {
      "--server.port=0",
      "--spring.datasource.url=" + DB.getJdbcUrl(),
      "--spring.datasource.username=" + DB.getUsername(),
      "--spring.datasource.password=" + DB.getPassword(),
      "--payment.require-tls=false",
      "--management.tracing.enabled=false"
    };
    try (var restarted =
        new org.springframework.boot.builder.SpringApplicationBuilder(PaymentApplication.class)
            .run(args)) {
      var recovered = restarted.getBean(PaymentService.class);
      assertEquals("COMPLETED", recovered.find(c.paymentId(), "alice").body().get("status"));
      assertEquals(200, recovered.transfer(c, "alice", "corr").http());
      assertEquals("499.000", recovered.balance("A", "alice").get("availableBalance"));
      assertTrue(recovered.reconcile().isEmpty());
    }
  }

  @Test
  void rejectionQueriesAndAdministrativeReports() throws Exception {
    var c = parser.parse(json(UUID.randomUUID().toString(), "600").getBytes(), "application/json");
    service.transfer(c, "alice", "corr");
    assertEquals("REJECTED", service.find(c.paymentId(), "alice").body().get("status"));
    var admin =
        jwt()
            .jwt(x -> x.subject("ops"))
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:admin"));
    mvc.perform(get("/api/v1/admin/retention").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minimumRetentionDays").value(2555));
    mvc.perform(get("/api/v1/admin/reconciliation").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issues").isEmpty());
  }

  @Test
  void originalOutcomeSurvivesDateWindowExpiry() {
    var parsed =
        parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    var c =
        new com.example.payments.domain.PaymentCommand(
            parsed.paymentId(),
            parsed.fromAccount(),
            parsed.toAccount(),
            parsed.amount(),
            Instant.now().minusSeconds(10),
            parsed.purpose(),
            parsed.notes());
    service.transfer(c, "alice", "corr");
    org.springframework.test.util.ReflectionTestUtils.setField(service, "ageSeconds", 1L);
    try {
      assertEquals(200, service.transfer(c, "alice", "corr").http());
      var fresh =
          new com.example.payments.domain.PaymentCommand(
              UUID.randomUUID().toString(),
              c.fromAccount(),
              c.toAccount(),
              c.amount(),
              c.date(),
              c.purpose(),
              c.notes());
      assertEquals(
          400,
          assertThrows(
                  com.example.payments.domain.ApiFailure.class,
                  () -> service.transfer(fresh, "alice", "corr"))
              .http);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(service, "ageSeconds", 2592000L);
    }
  }

  @Test
  void responseLostAfterCommitCanBeSafelyRetried() throws Exception {
    String id = UUID.randomUUID().toString(), body = json(id, "125.750");
    var auth =
        jwt()
            .jwt(x -> x.subject("alice"))
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));
    assertThrows(
        Exception.class,
        () ->
            mvc.perform(
                post("/api/v1/payments")
                    .with(auth)
                    .header("X-Test-Drop-Response", "true")
                    .contentType("application/json")
                    .content(body)));
    assertEquals("COMPLETED", service.find(id, "alice").body().get("status"));
    mvc.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("125.750"));
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(2, jdbc.queryForObject("select count(*) from ledger_entries", Integer.class));
  }

  @Test
  void explicitDelegatesAndReceivingRestrictions() {
    jdbc.update(
        "insert into account_permissions(principal,account_id,can_debit,can_view) values"
            + " ('delegate','A',true,true)");
    var c = parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    assertEquals(201, service.transfer(c, "delegate", "corr").http());
    assertEquals("499.000", service.balance("A", "delegate").get("availableBalance"));
    jdbc.update("update accounts set allow_incoming=false where id='B'");
    var denied =
        parser.parse(json(UUID.randomUUID().toString(), "1").getBytes(), "application/json");
    assertEquals(403, service.transfer(denied, "alice", "corr").http());
    assertEquals(new BigDecimal("499.000"), balance("A"));
  }

  @Test
  void httpCollectsTimeAndAmountValidationIssues() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var body =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(json(UUID.randomUUID().toString(), "0"));
    body.put("date", Instant.now().plusSeconds(86400).toString());
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(x -> x.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(mapper.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.issues.length()").value(2))
        .andExpect(request().attribute("paymentId", body.get("paymentId").asText()));
    assertEquals(0, jdbc.queryForObject("select count(*) from payments", Integer.class));
  }
}
