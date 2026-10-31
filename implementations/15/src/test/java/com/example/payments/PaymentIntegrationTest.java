package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.api.MessageParser;
import com.example.payments.application.PaymentService;
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
@SpringBootTest(
    properties = {
      "payments.require-https=false",
      "payments.rate-limit=10000",
      "management.tracing.enabled=false"
    })
@AutoConfigureMockMvc
class PaymentIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.flyway.user", postgres::getUsername);
    r.add("spring.flyway.password", postgres::getPassword);
  }

  @Autowired PaymentService service;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired MessageParser parser;

  @BeforeEach
  void setup() {
    db.execute("DROP TRIGGER IF EXISTS fail_after_debit ON accounts");
    db.execute(
        "TRUNCATE"
            + " payment_issues,ledger_entries,payments,account_debit_authorizations,account_destinations,accounts,customers"
            + " CASCADE");
    db.update(
        "INSERT INTO customers(id,display_name,subject)"
            + " VALUES('c1','Alice','alice'),('c2','Bob','bob')");
    db.update(
        "INSERT INTO accounts(id,customer_id,available_balance,opening_balance)"
            + " VALUES('A','c1',500.000,500.000),('B','c2',100.000,100.000)");
  }

  PaymentCommand command(UUID id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), Instant.now(), "invoice", "");
  }

  BigDecimal balance(String id) {
    return db.queryForObject(
        "SELECT available_balance FROM accounts WHERE id=?", BigDecimal.class, id);
  }

  int count(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  @Test
  void exactTransferPersistsReceiptAndBalancedLedger() {
    var c = command(UUID.randomUUID(), "125.750");
    var result = service.transfer(c, "alice", "corr");
    assertEquals(201, result.httpStatus());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(new BigDecimal("225.750"), balance("B"));
    assertEquals(2, count("ledger_entries"));
    assertEquals(
        BigDecimal.ZERO.setScale(3),
        db.queryForObject("SELECT sum(signed_amount) FROM ledger_entries", BigDecimal.class));
    assertEquals("alice", db.queryForObject("SELECT requested_by FROM payments", String.class));
    assertTrue(service.reconcile().healthy());
  }

  @Test
  void replayAfterLostResponseReturnsOriginalAndConflictDoesNotDebit() {
    var c = command(UUID.randomUUID(), "25");
    var first = service.transfer(c, "alice", "first");
    var replay = service.transfer(c, "alice", "second");
    assertEquals(200, replay.httpStatus());
    assertEquals(first.body(), replay.body());
    assertEquals(2, count("ledger_entries"));
    var changed =
        new PaymentCommand(
            c.paymentId(), "A", "B", new BigDecimal("26.000"), c.date(), c.purpose(), "");
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(PaymentException.class, () -> service.transfer(changed, "alice", "third"))
            .code());
    assertEquals(new BigDecimal("475.000"), balance("A"));
  }

  @Test
  void durableBusinessRejectionReturnsOriginalOutcome() {
    var c = command(UUID.randomUUID(), "501");
    var first = service.transfer(c, "alice", "one");
    var replay = service.transfer(c, "alice", "two");
    assertEquals(409, first.httpStatus());
    assertEquals(first.body(), replay.body());
    assertEquals("REJECTED", db.queryForObject("SELECT status FROM payments", String.class));
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void accountStatesAndAuthorizationFailWithoutFundsMovement() {
    assertEquals(
        403, service.transfer(command(UUID.randomUUID(), "1"), "mallory", "c").httpStatus());
    for (String state : List.of("FROZEN", "CLOSED")) {
      db.update("UPDATE accounts SET status=? WHERE id='B'", state);
      assertEquals(
          409, service.transfer(command(UUID.randomUUID(), "1"), "alice", "c").httpStatus());
    }
    var missing =
        new PaymentCommand(
            UUID.randomUUID(),
            "A",
            "MISSING",
            new BigDecimal("1.000"),
            Instant.now(),
            "missing account",
            "");
    assertEquals(403, service.transfer(missing, "alice", "c").httpStatus());
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void concurrentDuplicatesMoveFundsExactlyOnce() throws Exception {
    var c = command(UUID.randomUUID(), "100");
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return service.transfer(c, "alice", "race").httpStatus();
                }));
      gate.countDown();
      int created = 0;
      for (var f : results) {
        int code = f.get(20, TimeUnit.SECONDS);
        assertTrue(code == 200 || code == 201);
        if (code == 201) created++;
      }
      assertEquals(1, created);
    }
    assertEquals(new BigDecimal("400.000"), balance("A"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void concurrentDifferentPaymentsNeverOverdraw() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Integer>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        var c = command(UUID.randomUUID(), "100");
        jobs.add(() -> service.transfer(c, "alice", "race").httpStatus());
      }
      var results = pool.invokeAll(jobs);
      int success = 0;
      for (var f : results) if (f.get() == 201) success++;
      assertEquals(5, success);
    }
    assertEquals(new BigDecimal("0.000"), balance("A"));
    assertEquals(new BigDecimal("600.000"), balance("B"));
    assertEquals(10, count("ledger_entries"));
    assertTrue(service.reconcile().healthy());
  }

  @Test
  void failureAfterDebitRollsBackRegistrationAndBothBalances() {
    db.execute(
        "CREATE OR REPLACE FUNCTION inject_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.id='A' AND NEW.available_balance<OLD.available_balance THEN RAISE EXCEPTION"
            + " 'injected failure'; END IF; RETURN NEW; END $$");
    db.execute(
        "CREATE TRIGGER fail_after_debit AFTER UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION"
            + " inject_failure()");
    var c = command(UUID.randomUUID(), "10");
    assertThrows(PaymentException.class, () -> service.transfer(c, "alice", "rollback"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
    assertEquals(new BigDecimal("100.000"), balance("B"));
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void databaseRejectsLedgerMutationAndReconciliationDetectsCorruption() {
    service.transfer(command(UUID.randomUUID(), "10"), "alice", "audit");
    assertThrows(Exception.class, () -> db.update("UPDATE ledger_entries SET signed_amount=1"));
    assertThrows(Exception.class, () -> db.update("DELETE FROM payments"));
    db.update("UPDATE accounts SET available_balance=available_balance+1 WHERE id='B'");
    assertFalse(service.reconcile().healthy());
  }

  @Test
  void explicitGrantsDestinationPolicyAndDailyLimitsAreEnforced() {
    db.update(
        "INSERT INTO account_debit_authorizations(account_id,subject) VALUES('A','operator')");
    db.update("UPDATE accounts SET unrestricted_destinations=false WHERE id='A'");
    assertEquals(
        403, service.transfer(command(UUID.randomUUID(), "1"), "operator", "policy").httpStatus());
    db.update("INSERT INTO account_destinations VALUES('A','B')");
    db.update("UPDATE accounts SET daily_limit=2 WHERE id='A'");
    assertEquals(
        201, service.transfer(command(UUID.randomUUID(), "2"), "operator", "policy").httpStatus());
    assertEquals(
        409, service.transfer(command(UUID.randomUUID(), "1"), "operator", "policy").httpStatus());
    assertEquals(new BigDecimal("498.000"), balance("A"));
  }

  @Test
  void boundedLockTimeoutIsTransientAndDoesNotPersistRejection() throws Exception {
    try (var connection =
        java.sql.DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT id FROM accounts WHERE id='A' FOR UPDATE")) {
        statement.executeQuery();
      }
      long started = System.nanoTime();
      var error =
          assertThrows(
              PaymentException.class,
              () -> service.transfer(command(UUID.randomUUID(), "1"), "alice", "lock"));
      assertEquals("TEMPORARILY_UNAVAILABLE", error.code());
      assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds() < 12);
      connection.rollback();
    }
    assertEquals(0, count("payments"));
    assertEquals(0, count("ledger_entries"));
    assertEquals(new BigDecimal("500.000"), balance("A"));
  }

  @Test
  void historicalCommittedInstructionReplaysAfterValidationWindowExpires() throws Exception {
    var old =
        new PaymentCommand(
            UUID.randomUUID(),
            "A",
            "B",
            new BigDecimal("1.000"),
            Instant.now().minusSeconds(40L * 86400),
            "historical",
            "");
    // Represents an instruction originally committed while its date was current.
    service.transfer(old, "alice", "original");
    String body =
        "{\"paymentId\":\""
            + old.paymentId()
            + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":1,\"date\":\""
            + old.date()
            + "\",\"purpose\":\"historical\"}";
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    assertEquals(new BigDecimal("499.000"), balance("A"));
    assertEquals(2, count("ledger_entries"));
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(body.replace(old.paymentId().toString(), UUID.randomUUID().toString())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void lostCommitAcknowledgementIsTransientAndRetryFindsCommittedReceipt() {
    var manager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()) {
          @Override
          protected void doCommit(
              org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            throw new org.springframework.transaction.TransactionSystemException(
                "simulated lost commit acknowledgement");
          }
        };
    var uncertain =
        new PaymentService(
            db,
            new com.example.payments.persistence.AccountRepository(db),
            manager,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            java.time.Clock.systemUTC(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            2);
    var instruction = command(UUID.randomUUID(), "10");
    var error =
        assertThrows(
            PaymentException.class, () -> uncertain.transfer(instruction, "alice", "uncertain"));
    assertEquals(503, error.httpStatus());
    assertEquals(200, service.transfer(instruction, "alice", "retry").httpStatus());
    assertEquals(new BigDecimal("490.000"), balance("A"));
    assertEquals(2, count("ledger_entries"));
  }

  String document(UUID id) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\"invoice\"}";
  }

  @Test
  void httpSuccessNegotiationAuthorizationAndLookup() throws Exception {
    UUID id = UUID.randomUUID();
    String body = document(id);
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value("125.750"))
        .andExpect(header().string("Location", "/api/v1/payments/" + id))
        .andExpect(header().exists("X-Correlation-ID"));
    mvc.perform(
            get("/api/v1/payments/" + id)
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
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
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableBalance").value("374.250"));
    mvc.perform(
            get("/api/v1/accounts/A/balance")
                .with(
                    jwt()
                        .jwt(j -> j.subject("mallory"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:read"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .accept("application/xml")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/xml"))
        .andExpect(xpath("/response/amount").string("125.750"));
  }

  @Test
  void xmlInputAndJsonReplayUseSamePath() throws Exception {
    UUID id = UUID.randomUUID();
    String date = Instant.now().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/xml")
                .content(xml))
        .andExpect(status().isCreated());
    String equivalent =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "paymentId",
                    id.toString(),
                    "fromAccount",
                    "A",
                    "toAccount",
                    "B",
                    "amount",
                    "125.75",
                    "date",
                    date,
                    "purpose",
                    "invoice"));
    mvc.perform(
            post("/api/v1/payments")
                .with(
                    jwt()
                        .jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                .contentType("application/json")
                .content(equivalent))
        .andExpect(status().isOk());
    assertEquals(new BigDecimal("374.250"), balance("A"));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void apiErrorsHaveStableContractAndNoSideEffects() throws Exception {
    mvc.perform(post("/api/v1/payments").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    var user =
        jwt()
            .jwt(j -> j.subject("alice"))
            .authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));
    mvc.perform(post("/api/v1/payments").with(user).contentType("text/plain").content("a"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    mvc.perform(
            post("/api/v1/payments")
                .with(user)
                .contentType("application/json")
                .accept("image/png")
                .content(document(UUID.randomUUID())))
        .andExpect(status().isNotAcceptable());
    mvc.perform(
            post("/api/v1/payments").with(user).contentType("application/json").content("{bad}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_MESSAGE"));
    mvc.perform(post("/api/v1/payments").with(user).contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.issues.length()").value(6));
    assertEquals(0, count("payments"));
  }
}
