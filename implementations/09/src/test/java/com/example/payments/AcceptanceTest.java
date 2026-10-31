package com.example.payments;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.api.MessageCodec;
import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AcceptanceTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired PaymentService service;
  @Autowired MessageCodec codec;
  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers RESTART IDENTITY"
            + " CASCADE");
    db.update(
        "INSERT INTO customers(id,display_name,status)"
            + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
    db.update(
        "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
            + " VALUES('ACC-1001','alice','JOD',500,500,'ACTIVE'),('ACC-2002','bob','JOD',100,100,'ACTIVE')");
  }

  String payload(UUID id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" Invoice \"}";
  }

  PaymentCommand command(UUID id, String amount) {
    return codec.read(new ByteArrayInputStream(payload(id, amount).getBytes()), "application/json");
  }

  MockHttpServletRequestBuilder postJson(String payload) {
    return post("/api/v1/payments")
        .with(
            jwt()
                .jwt(
                    j ->
                        j.subject("alice")
                            .claim(
                                "scope",
                                "payments:write payments:read accounts:read payments:admin")))
        .contentType("application/json")
        .content(payload);
  }

  String correlation() {
    return UUID.randomUUID().toString();
  }

  void balances(String a, String b) {
    assertThat(
            db.queryForObject(
                "SELECT available_balance FROM accounts WHERE id='ACC-1001'", BigDecimal.class))
        .isEqualByComparingTo(a);
    assertThat(
            db.queryForObject(
                "SELECT available_balance FROM accounts WHERE id='ACC-2002'", BigDecimal.class))
        .isEqualByComparingTo(b);
  }

  @Test
  void jsonSuccess() throws Exception {
    mvc.perform(postJson(payload(UUID.randomUUID(), "125.750")))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.amount").value("125.750"));
    balances("374.250", "225.750");
    assertThat(db.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class))
        .isEqualTo(2);
    assertThat(service.reconcile().get("balanced")).isEqualTo(true);
  }

  @Test
  void xmlAndCrossFormatReplay() throws Exception {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>Invoice</purpose></payment>";
    mvc.perform(postJson(xml).contentType("application/xml").accept("application/xml"))
        .andExpect(status().isCreated())
        .andExpect(xpath("/receipt/amount").string("125.750"));
    String jsonPayload =
        payload(id, "125.75")
            .replaceAll("\\\"date\\\":\\\"[^\\\"]+\\\"", "\"date\":\"" + date + "\"");
    mvc.perform(postJson(jsonPayload)).andExpect(status().isOk());
    balances("374.250", "225.750");
  }

  @Test
  void replayReturnsOriginalAfterResponseLossAndConflictDoesNotMoveMoney() throws Exception {
    UUID id = UUID.randomUUID();
    String p = payload(id, "125.750");
    String original =
        mvc.perform(postJson(p))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String retry =
        mvc.perform(postJson(p))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(retry)).isEqualTo(json.readTree(original));
    mvc.perform(postJson(p.replace("125.750", "125.751")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    balances("374.250", "225.750");
  }

  @Test
  void rejectionIsDurableAndImmutable() throws Exception {
    String p = payload(UUID.randomUUID(), "501.000");
    String first =
        mvc.perform(postJson(p))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(postJson(p))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(first)).isEqualTo(json.readTree(second));
    balances("500", "100");
    assertThat(db.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isZero();
    assertThatThrownBy(() -> db.update("UPDATE payments SET purpose='changed'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void concurrentDuplicateMovesFundsOnce() throws Exception {
    PaymentCommand c = command(UUID.randomUUID(), "50");
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> jobs = new ArrayList<>();
      for (int i = 0; i < 16; i++)
        jobs.add(() -> service.transfer(c, "alice", correlation()).http());
      List<Integer> outcomes = new ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(jobs)) outcomes.add(f.get());
      assertThat(outcomes)
          .containsExactlyInAnyOrderElementsOf(
              java.util.stream.IntStream.range(0, 16).mapToObj(i -> i == 0 ? 201 : 200).toList());
    }
    balances("450", "150");
    assertThat(db.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void concurrentWithdrawalsNeverOverdraw() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> jobs = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        PaymentCommand c = command(UUID.randomUUID(), "100");
        jobs.add(() -> service.transfer(c, "alice", correlation()).http());
      }
      List<Integer> results = new ArrayList<>();
      for (var f : pool.invokeAll(jobs)) results.add(f.get());
      assertThat(results.stream().filter(i -> i == 201).count()).isEqualTo(5);
      assertThat(results.stream().filter(i -> i == 409).count()).isEqualTo(7);
    }
    balances("0", "600");
    assertThat(service.reconcile().get("balanced")).isEqualTo(true);
  }

  @Test
  void oppositeDirectionDoesNotDeadlock() throws Exception {
    PaymentCommand a = command(UUID.randomUUID(), "10"),
        b =
            new PaymentCommand(
                UUID.randomUUID(),
                "ACC-2002",
                "ACC-1001",
                new BigDecimal("10.000"),
                a.date(),
                "reverse",
                null);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      var futures =
          pool.invokeAll(
              List.of(
                  () -> service.transfer(a, "alice", correlation()),
                  () -> service.transfer(b, "bob", correlation())));
      for (var f : futures) assertThat(((PaymentService.Result) f.get()).http()).isEqualTo(201);
    }
    balances("500", "100");
  }

  @Test
  void failureAfterDebitRollsBackEverything() {
    db.execute(
        "CREATE FUNCTION test_fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.id='ACC-2002' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    db.execute(
        "CREATE TRIGGER test_fail BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION"
            + " test_fail_credit()");
    try {
      assertThatThrownBy(
              () -> service.transfer(command(UUID.randomUUID(), "25"), "alice", correlation()))
          .isInstanceOf(ApiError.class);
      balances("500", "100");
      assertThat(db.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isZero();
    } finally {
      db.execute("DROP TRIGGER test_fail ON accounts");
      db.execute("DROP FUNCTION test_fail_credit()");
    }
  }

  @Test
  void databaseLockTimeoutIsBoundedAndRetryable() throws Exception {
    PaymentCommand c = command(UUID.randomUUID(), "10");
    try (var connection =
        java.sql.DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setAutoCommit(false);
      connection.createStatement().execute("SELECT * FROM accounts WHERE id='ACC-1001' FOR UPDATE");
      long start = System.nanoTime();
      assertThatThrownBy(() -> service.transfer(c, "alice", correlation()))
          .isInstanceOfSatisfying(
              ApiError.class,
              e -> {
                assertThat(e.http).isEqualTo(503);
                assertThat(e.code).isEqualTo("TEMPORARILY_UNAVAILABLE");
              });
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds()).isLessThan(9);
      connection.rollback();
    }
    balances("500", "100");
    assertThat(db.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void authorizationAndHiddenLookups() throws Exception {
    mvc.perform(
            post("/api/v1/payments")
                .contentType("application/json")
                .content(payload(UUID.randomUUID(), "1")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(
            postJson(payload(UUID.randomUUID(), "1"))
                .with(jwt().jwt(j -> j.subject("bob").claim("scope", "payments:write"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/accounts/ACC-1001/balance")
                .with(jwt().jwt(j -> j.subject("bob").claim("scope", "accounts:read"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/payments/" + UUID.randomUUID())
                .with(jwt().jwt(j -> j.subject("bob").claim("scope", "payments:read"))))
        .andExpect(status().isNotFound());
    balances("500", "100");
  }

  @Test
  void explicitDebitDelegationWorks() {
    db.update(
        "INSERT INTO account_permissions(principal,account_id,can_debit)"
            + " VALUES('delegate','ACC-1001',true)");
    assertThat(service.transfer(command(UUID.randomUUID(), "1"), "delegate", correlation()).http())
        .isEqualTo(201);
  }

  @Test
  void frozenAndUnknownAccountsAndRestrictions() throws Exception {
    db.update("UPDATE accounts SET status='FROZEN' WHERE id='ACC-2002'");
    mvc.perform(postJson(payload(UUID.randomUUID(), "1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    db.update("UPDATE accounts SET status='ACTIVE',allow_receive=false WHERE id='ACC-2002'");
    mvc.perform(postJson(payload(UUID.randomUUID(), "1"))).andExpect(status().isForbidden());
    mvc.perform(postJson(payload(UUID.randomUUID(), "1").replace("ACC-2002", "unknown")))
        .andExpect(status().isForbidden());
    db.update("UPDATE accounts SET transfer_limit=0.500 WHERE id='ACC-1001'");
    mvc.perform(postJson(payload(UUID.randomUUID(), "1"))).andExpect(status().isForbidden());
    balances("500", "100");
  }

  @Test
  void policyLimitRejected() {
    db.update("UPDATE accounts SET transfer_limit=1 WHERE id='ACC-1001'");
    var result = service.transfer(command(UUID.randomUUID(), "2"), "alice", correlation());
    assertThat(result.body().get("code")).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
  }

  @Test
  void negotiationAndMalformedRequestsDoNotMoveFunds() throws Exception {
    mvc.perform(postJson("{")).andExpect(status().isBadRequest());
    mvc.perform(postJson(payload(UUID.randomUUID(), "1")).contentType("text/plain"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(postJson(payload(UUID.randomUUID(), "1")).accept("text/html"))
        .andExpect(status().isNotAcceptable());
    mvc.perform(postJson(payload(UUID.randomUUID(), "1.0001"))).andExpect(status().isBadRequest());
    balances("500", "100");
    assertThat(db.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void reconciliationDetectsCorruptBalance() {
    service.transfer(command(UUID.randomUUID(), "1"), "alice", correlation());
    db.update("UPDATE accounts SET available_balance=999 WHERE id='ACC-1001'");
    assertThat(service.reconcile().get("balanced")).isEqualTo(false);
  }

  @Test
  void ledgerIsImmutableAndConstraintRejectsIncompletePayment() {
    service.transfer(command(UUID.randomUUID(), "1"), "alice", correlation());
    assertThatThrownBy(() -> db.update("DELETE FROM ledger_entries"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> db.update("UPDATE ledger_entries SET signed_amount=2 WHERE entry_type='CREDIT'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void restartKeepsCommittedState() {
    String[] args = {
      "--server.port=0",
      "--spring.datasource.url=" + postgres.getJdbcUrl(),
      "--spring.datasource.username=" + postgres.getUsername(),
      "--spring.datasource.password=" + postgres.getPassword(),
      "--payments.require-tls=false",
      "--spring.flyway.locations=classpath:db/migration,classpath:db/dev"
    };
    PaymentCommand c = command(UUID.randomUUID(), "12");
    try (var app =
        new org.springframework.boot.builder.SpringApplicationBuilder(PaymentApplication.class)
            .run(args)) {
      assertThat(app.getBean(PaymentService.class).transfer(c, "alice", correlation()).http())
          .isEqualTo(201);
    }
    try (var app =
        new org.springframework.boot.builder.SpringApplicationBuilder(PaymentApplication.class)
            .run(args)) {
      var persisted = app.getBean(PaymentService.class);
      assertThat(persisted.find(c.paymentId(), "alice").get("status")).isEqualTo("COMPLETED");
      assertThat(persisted.transfer(c, "alice", correlation()).http()).isEqualTo(200);
    }
    balances("488", "112");
  }
}
