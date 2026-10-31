package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.api.*;
import com.example.payments.application.*;
import com.example.payments.domain.*;
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
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIntegrationTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
    r.add("payments.require-tls", () -> false);
  }

  @Autowired PaymentService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired Reconciliation reconciliation;

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE ledger_entries,payments,account_permissions,accounts,customers RESTART IDENTITY"
            + " CASCADE");
    jdbc.update(
        "INSERT INTO customers(id,display_name,status) VALUES"
            + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
    jdbc.update(
        "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES"
            + " ('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')");
  }

  Command command(UUID id, String amount) {
    return new Command(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "Invoice", "");
  }

  @Test
  void moneyAndReplayAndConflict() {
    var c = command(UUID.randomUUID(), "125.750");
    assertEquals(201, service.transfer(c, "alice").http());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    assertEquals(200, service.transfer(c, "alice").http());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        service
            .transfer(
                new Command(
                    c.paymentId(), "A", "B", new BigDecimal("1.000"), c.date(), c.purpose(), ""),
                "alice")
            .code());
    assertTrue(reconciliation.report().isEmpty());
  }

  @Test
  void rejectionIsDurable() {
    var c = command(UUID.randomUUID(), "600");
    assertEquals("INSUFFICIENT_FUNDS", service.transfer(c, "alice").code());
    jdbc.update("UPDATE accounts SET available_balance=700 WHERE id='A'");
    assertEquals("INSUFFICIENT_FUNDS", service.transfer(c, "alice").code());
    assertEquals(new BigDecimal("700.000"), balance("A"));
  }

  @Test
  void permissionAndHiddenLookups() throws Exception {
    assertEquals("FORBIDDEN", service.transfer(command(UUID.randomUUID(), "1"), "bob").code());
    mvc.perform(
            get("/api/v1/accounts/A/balance")
                .with(
                    jwt()
                        .jwt(j -> j.subject("bob"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/accounts/A/balance"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void duplicatesAndConcurrentSpending() throws Exception {
    var c = command(UUID.randomUUID(), "125.750");
    try (var pool = Executors.newFixedThreadPool(8)) {
      var start = new CountDownLatch(1);
      List<Future<Integer>> fs = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        fs.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.transfer(c, "alice").http();
                }));
      start.countDown();
      int created = 0;
      for (var f : fs) if (f.get(20, TimeUnit.SECONDS) == 201) created++;
      assertEquals(1, created);
      assertEquals(new BigDecimal("374.250"), balance("A"));
      List<Future<PaymentService.Result>> spend = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        spend.add(pool.submit(() -> service.transfer(command(UUID.randomUUID(), "100"), "alice")));
      for (var f : spend) f.get(20, TimeUnit.SECONDS);
      assertEquals(new BigDecimal("74.250"), balance("A"));
      assertTrue(reconciliation.report().isEmpty());
    }
  }

  @Test
  void rollbackAfterDebitAndReceiptSurvivesNewService() {
    jdbc.execute(
        "CREATE FUNCTION fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='B'"
            + " THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER inject_failure BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION"
            + " fail_credit()");
    try {
      assertThrows(
          RuntimeException.class, () -> service.transfer(command(UUID.randomUUID(), "1"), "alice"));
      assertEquals(new BigDecimal("500.000"), balance("A"));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class));
    } finally {
      jdbc.execute("DROP TRIGGER inject_failure ON accounts");
      jdbc.execute("DROP FUNCTION fail_credit()");
    }
    var c = command(UUID.randomUUID(), "1");
    service.transfer(c, "alice");
    assertEquals(200, service.transfer(c, "alice").http());
    assertEquals("COMPLETED", service.find(c.paymentId(), "alice").get("status"));
  }

  @Test
  void xmlJsonAndNegotiation() throws Exception {
    String id = UUID.randomUUID().toString(), date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>Invoice</purpose></payment>";
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/xml")
                .accept("application/xml")
                .content(xml))
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith("application/xml"));
    String json =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"125.75\",\"date\":\""
            + date
            + "\",\"purpose\":\"Invoice\"}";
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("125.750"));
    mvc.perform(
            post("/api/v1/payments")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("text/plain")
                .content("x"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            get("/api/v1/accounts/A/balance")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read")))
                .accept("text/plain"))
        .andExpect(status().isNotAcceptable());
  }

  @Test
  void corruptionDetectedAndLedgerImmutable() {
    service.transfer(command(UUID.randomUUID(), "1"), "alice");
    assertThrows(Exception.class, () -> jdbc.update("DELETE FROM ledger_entries"));
    jdbc.update("UPDATE accounts SET available_balance=available_balance+1 WHERE id='A'");
    assertFalse(reconciliation.report().isEmpty());
  }

  @Test
  void frozenAndLimitAndMissingDestination() {
    jdbc.update("UPDATE accounts SET status='FROZEN' WHERE id='A'");
    assertEquals(
        "ACCOUNT_NOT_ACTIVE", service.transfer(command(UUID.randomUUID(), "1"), "alice").code());
    jdbc.update("UPDATE accounts SET status='ACTIVE',transfer_limit=2 WHERE id='A'");
    assertEquals(
        "PAYMENT_LIMIT_EXCEEDED",
        service.transfer(command(UUID.randomUUID(), "3"), "alice").code());
    var c = command(UUID.randomUUID(), "1");
    assertEquals(
        "FORBIDDEN",
        service
            .transfer(
                new Command(c.paymentId(), "A", "missing", c.amount(), c.date(), c.purpose(), ""),
                "alice")
            .code());
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void missingScopeAndBodyLimitAndXmlAttack() throws Exception {
    mvc.perform(post("/api/v1/payments").with(jwt()).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            post("/api/v1/payments")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content("x".repeat(65537)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            post("/api/v1/payments")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/xml")
                .content("<!DOCTYPE payment SYSTEM 'http://127.0.0.1:1/private'><payment/>"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_MESSAGE"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void authorizedDelegateAndReadiness() throws Exception {
    jdbc.update(
        "INSERT INTO account_permissions(account_id,principal,can_debit,can_view) VALUES"
            + " ('A','operator',true,true)");
    assertEquals(201, service.transfer(command(UUID.randomUUID(), "1"), "operator").http());
    assertEquals("499.000", service.balance("A", "operator").get("balance"));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void boundedLockTimeoutDoesNotRecordInfrastructureRejection() throws Exception {
    var dataSource = jdbc.getDataSource();
    try (var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var statement =
          holder.prepareStatement("SELECT id FROM accounts WHERE id='A' FOR UPDATE")) {
        statement.executeQuery();
      }
      var c = command(UUID.randomUUID(), "1");
      long started = System.nanoTime();
      var error = assertThrows(ApiException.class, () -> service.transfer(c, "alice"));
      assertEquals(503, error.http);
      assertEquals("TEMPORARILY_UNAVAILABLE", error.code);
      assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds() < 12);
      holder.rollback();
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM payments WHERE payment_id=?", Integer.class, c.paymentId()));
      assertEquals(new BigDecimal("500.000"), balance("A"));
    }
  }

  @Test
  void ledgerInsertionFailureRollsBackBothBalancesAndPayment() {
    jdbc.execute(
        "CREATE FUNCTION fail_ledger_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.entry_type='CREDIT' THEN RAISE EXCEPTION 'injected ledger failure'; END IF;"
            + " RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER inject_ledger_failure BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE"
            + " FUNCTION fail_ledger_credit()");
    try {
      assertThrows(
          RuntimeException.class, () -> service.transfer(command(UUID.randomUUID(), "1"), "alice"));
      assertEquals(new BigDecimal("500.000"), balance("A"));
      assertEquals(new BigDecimal("100.000"), balance("B"));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    } finally {
      jdbc.execute("DROP TRIGGER inject_ledger_failure ON ledger_entries");
      jdbc.execute("DROP FUNCTION fail_ledger_credit()");
    }
  }

  BigDecimal balance(String id) {
    return jdbc.queryForObject(
        "SELECT available_balance FROM accounts WHERE id=?", BigDecimal.class, id);
  }
}
