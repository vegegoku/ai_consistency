package com.example.payments;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.PaymentCommand;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.math.BigDecimal;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"payments.require-tls=false", "payments.rate-per-minute=100000"})
@AutoConfigureMockMvc
@Testcontainers
@Import(PaymentIntegrationTest.TestBeans.class)
class PaymentIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://issuer.test");
  }

  static final AtomicBoolean FAIL = new AtomicBoolean();
  static final KeyPair KEYS = keys();

  static KeyPair keys() {
    try {
      var g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    PaymentService.FaultInjector fail() {
      return () -> {
        if (FAIL.get()) throw new IllegalStateException("injected");
      };
    }

    @Bean
    @Primary
    JwtDecoder testDecoder() {
      var d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
      d.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer("https://issuer.test"),
              new JwtClaimValidator<List<String>>(
                  "aud", v -> v != null && v.contains("payments"))));
      return d;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired PaymentService service;

  @BeforeEach
  void seed() {
    FAIL.set(false);
    db.execute(
        "TRUNCATE ledger_entries,payments,account_debit_grants,accounts,customers RESTART IDENTITY"
            + " CASCADE");
    db.update(
        "INSERT INTO customers(id,display_name,status) VALUES"
            + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
    db.update(
        "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)"
            + " VALUES ('A','alice','JOD',500,500,'ACTIVE'),('B','bob','JOD',100,100,'ACTIVE')");
  }

  org.springframework.test.web.servlet.request.RequestPostProcessor caller(String who) {
    return jwt()
        .jwt(j -> j.subject(who))
        .authorities(
            new SimpleGrantedAuthority("SCOPE_payments:write"),
            new SimpleGrantedAuthority("SCOPE_payments:read"),
            new SimpleGrantedAuthority("SCOPE_accounts:read"),
            new SimpleGrantedAuthority("SCOPE_payments:admin"));
  }

  String payload(String id, String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + TIME
        + "\",\"purpose\":\" invoice \"}";
  }

  static final Instant TIME = Instant.now();

  PaymentCommand command(String id, String amount) {
    return new PaymentCommand(
        id, "A", "B", new BigDecimal(amount).setScale(3), TIME, "invoice", "");
  }

  ResultActions submit(String id, String amount) throws Exception {
    return mvc.perform(
        post("/api/v1/payments")
            .with(caller("alice"))
            .contentType("application/json")
            .content(payload(id, amount)));
  }

  BigDecimal balance(String id) {
    return db.queryForObject(
        "SELECT available_balance FROM accounts WHERE id=?", BigDecimal.class, id);
  }

  int count(String table) {
    return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  @Test
  void jsonSuccessAndAuthorizedLookups() throws Exception {
    String id = UUID.randomUUID().toString();
    submit(id, "125.750")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value("125.750"))
        .andExpect(header().string("Location", "/api/v1/payments/" + id));
    assertThat(balance("A")).isEqualByComparingTo("374.250");
    assertThat(balance("B")).isEqualByComparingTo("225.750");
    assertThat(count("ledger_entries")).isEqualTo(2);
    mvc.perform(get("/api/v1/payments/" + id).with(caller("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    mvc.perform(get("/api/v1/accounts/A/balance").with(caller("alice")))
        .andExpect(jsonPath("$.balance").value("374.250"));
    mvc.perform(get("/api/v1/accounts/A/balance").with(caller("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/payments/" + id).with(caller("stranger")))
        .andExpect(status().isNotFound());
  }

  @Test
  void xmlSuccessAndCrossFormatReplay() throws Exception {
    String id = UUID.randomUUID().toString();
    String xml =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"
            + TIME
            + "</date><purpose>invoice</purpose></payment>";
    mvc.perform(
            post("/api/v1/payments")
                .with(caller("alice"))
                .contentType("application/xml")
                .accept("application/xml")
                .content(xml))
        .andExpect(status().isCreated())
        .andExpect(xpath("/response/amount").string("125.750"));
    submit(id, "125.75").andExpect(status().isOk());
    assertThat(count("ledger_entries")).isEqualTo(2);
  }

  @Test
  void retriesOriginalReceiptAfterResponseLossAndConflicts() throws Exception {
    String id = UUID.randomUUID().toString();
    String original = submit(id, "125.750").andReturn().getResponse().getContentAsString();
    submit(id, "125.750").andExpect(status().isOk()).andExpect(content().json(original));
    submit(id, "125.751")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(balance("A")).isEqualByComparingTo("374.250");
  }

  @Test
  void insufficientFundsRejectionIsDurableAndReplayStable() throws Exception {
    String id = UUID.randomUUID().toString();
    String first =
        submit(id, "501")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    submit(id, "501").andExpect(content().json(first));
    assertThat(balance("A")).isEqualByComparingTo("500");
    assertThat(count("ledger_entries")).isZero();
    assertThat(count("payments")).isOne();
  }

  @Test
  void authorizationFrozenAndMissingAccountsCannotMoveFunds() throws Exception {
    mvc.perform(
            post("/api/v1/payments")
                .with(caller("bob"))
                .contentType("application/json")
                .content(payload(UUID.randomUUID().toString(), "1")))
        .andExpect(status().isForbidden());
    db.update("UPDATE accounts SET status='FROZEN' WHERE id='B'");
    submit(UUID.randomUUID().toString(), "1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    db.update("UPDATE accounts SET id='C' WHERE id='B'");
    submit(UUID.randomUUID().toString(), "1").andExpect(status().isForbidden());
    assertThat(balance("A")).isEqualByComparingTo("500");
  }

  @Test
  void malformedPrecisionAndXxeHaveNoEffects() throws Exception {
    for (String content :
        List.of(
            "{",
            "{}",
            payload(UUID.randomUUID().toString(), "1.0001"),
            payload(UUID.randomUUID().toString(), "0")))
      mvc.perform(
              post("/api/v1/payments")
                  .with(caller("alice"))
                  .contentType("application/json")
                  .content(content))
          .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/payments")
                .with(caller("alice"))
                .contentType("application/xml")
                .content(
                    "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                        + " 'http://127.0.0.1:1/'>]><payment><purpose>&x;</purpose></payment>"))
        .andExpect(status().isBadRequest());
    assertThat(count("payments")).isZero();
  }

  @Test
  void negotiationAndPayloadLimits() throws Exception {
    mvc.perform(
            post("/api/v1/payments").with(caller("alice")).contentType("text/plain").content("{}"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(
            post("/api/v1/payments")
                .with(caller("alice"))
                .contentType("application/json")
                .accept("text/plain")
                .content("{}"))
        .andExpect(status().isNotAcceptable());
    mvc.perform(
            post("/api/v1/payments")
                .with(caller("alice"))
                .contentType("application/json")
                .content(" ".repeat(65537)))
        .andExpect(status().isBadRequest());
    assertThat(count("payments")).isZero();
  }

  @Test
  void dailyLimitAndReceiveRestriction() throws Exception {
    db.update("UPDATE accounts SET daily_limit=10 WHERE id='A'");
    submit(UUID.randomUUID().toString(), "11")
        .andExpect(jsonPath("$.code").value("PAYMENT_LIMIT_EXCEEDED"));
    db.update("UPDATE accounts SET receive_allowed=false WHERE id='B'");
    submit(UUID.randomUUID().toString(), "1").andExpect(status().isForbidden());
  }

  @Test
  void rollbackAfterDebit() {
    FAIL.set(true);
    String id = UUID.randomUUID().toString();
    assertThatThrownBy(
            () -> service.transfer(command(id, "10"), "alice", UUID.randomUUID().toString()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(balance("A")).isEqualByComparingTo("500");
    assertThat(balance("B")).isEqualByComparingTo("100");
    assertThat(count("payments")).isZero();
    assertThat(count("ledger_entries")).isZero();
  }

  @Test
  void duplicateRaceMovesFundsOnce() throws Exception {
    String id = UUID.randomUUID().toString();
    var results =
        race(12, () -> service.transfer(command(id, "10"), "alice", UUID.randomUUID().toString()));
    assertThat(results.stream().filter(r -> r.http() == 201).count()).isOne();
    assertThat(balance("A")).isEqualByComparingTo("490");
    assertThat(count("ledger_entries")).isEqualTo(2);
  }

  @Test
  void concurrentDistinctPaymentsCannotOverdraw() throws Exception {
    var results =
        race(
            12,
            () ->
                service.transfer(
                    command(UUID.randomUUID().toString(), "100"),
                    "alice",
                    UUID.randomUUID().toString()));
    assertThat(results.stream().filter(r -> r.http() == 201).count()).isEqualTo(5);
    assertThat(balance("A")).isEqualByComparingTo("0");
    assertThat(balance("B")).isEqualByComparingTo("600");
    assertThat(count("ledger_entries")).isEqualTo(10);
  }

  List<PaymentService.Result> race(int n, Callable<PaymentService.Result> action) throws Exception {
    try (var pool = Executors.newFixedThreadPool(n)) {
      var ready = new CountDownLatch(n);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<PaymentService.Result>>();
      for (int i = 0; i < n; i++)
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return action.call();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var results = new ArrayList<PaymentService.Result>();
      for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
      return results;
    }
  }

  @Test
  void ledgerIsImmutableAndReconciliationDetectsCorruption() throws Exception {
    submit(UUID.randomUUID().toString(), "10").andExpect(status().isCreated());
    assertThat(service.reconcile().get("healthy")).isEqualTo(true);
    assertThatThrownBy(() -> db.update("UPDATE ledger_entries SET balance_after=0"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    db.update("UPDATE accounts SET available_balance=available_balance+1 WHERE id='A'");
    assertThat(service.reconcile().get("healthy")).isEqualTo(false);
  }

  @Test
  void missingInvalidAndWrongScopeAuthentication() throws Exception {
    mvc.perform(get("/api/v1/accounts/A/balance"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/v1/accounts/A/balance").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/payments").with(jwt()).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }

  String token(String issuer, String audience, Date expiration) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject("alice")
            .issuer(issuer)
            .audience(audience)
            .expirationTime(expiration)
            .claim("scope", "accounts:read")
            .build();
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    signed.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
    return signed.serialize();
  }

  @Test
  void jwtSignatureIssuerAudienceExpirationEnforced() throws Exception {
    String valid =
        token("https://issuer.test", "payments", Date.from(Instant.now().plusSeconds(300)));
    mvc.perform(get("/api/v1/accounts/A/balance").header("Authorization", "Bearer " + valid))
        .andExpect(status().isOk());
    for (String token :
        List.of(
            token("wrong", "payments", Date.from(Instant.now().plusSeconds(300))),
            token("https://issuer.test", "wrong", Date.from(Instant.now().plusSeconds(300))),
            token("https://issuer.test", "payments", Date.from(Instant.now().minusSeconds(300))),
            valid.substring(0, valid.length() - 10) + "AAAAAAAAAA"))
      mvc.perform(get("/api/v1/accounts/A/balance").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
  }

  @Test
  void statusSurvivesNewServiceInstance() {
    String id = UUID.randomUUID().toString();
    service.transfer(command(id, "10"), "alice", UUID.randomUUID().toString());
    var restarted =
        new PaymentService(
            new com.example.payments.persistence.PaymentStore(db),
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    db.getDataSource())),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            new BigDecimal("50000"),
            () -> {});
    assertThat(restarted.get(id, "alice").get("status")).isEqualTo("COMPLETED");
    assertThat(restarted.transfer(command(id, "10"), "alice", UUID.randomUUID().toString()).http())
        .isEqualTo(200);
  }

  @Test
  void lockTimeoutIsBoundedAndLeavesNoPermanentRejection() throws Exception {
    try (var connection = db.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
      }
      String id = UUID.randomUUID().toString();
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> service.transfer(command(id, "10"), "alice", UUID.randomUUID().toString()))
          .isInstanceOf(com.example.payments.domain.PaymentException.class)
          .hasMessage("TEMPORARILY_UNAVAILABLE");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
      connection.rollback();
      assertThat(count("payments")).isZero();
      assertThat(balance("A")).isEqualByComparingTo("500");
      assertThat(service.transfer(command(id, "10"), "alice", UUID.randomUUID().toString()).http())
          .isEqualTo(201);
    }
  }

  @Test
  void databaseRejectsTerminalPaymentWithoutLedger() {
    assertThatThrownBy(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                            db.getDataSource()))
                    .execute(
                        s -> {
                          db.update(
                              "INSERT INTO"
                                  + " payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id,processed_at)"
                                  + " VALUES"
                                  + " (?::uuid,'A','B',1,'JOD',now(),'invoice','',repeat('a',64),'COMPLETED','alice',?::uuid,now())",
                              UUID.randomUUID().toString(),
                              UUID.randomUUID().toString());
                          return null;
                        }))
        .isInstanceOf(org.springframework.transaction.TransactionException.class);
    assertThat(count("payments")).isZero();
  }

  @Test
  void postCommitMetricsFailureCannotReplaceReceipt() {
    var registry =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry() {
          @Override
          protected io.micrometer.core.instrument.Counter newCounter(
              io.micrometer.core.instrument.Meter.Id id) {
            throw new IllegalStateException("telemetry unavailable");
          }
        };
    var isolated =
        new PaymentService(
            new com.example.payments.persistence.PaymentStore(db),
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    db.getDataSource())),
            registry,
            new BigDecimal("50000"),
            () -> {});
    assertThat(
            isolated
                .transfer(
                    command(UUID.randomUUID().toString(), "10"),
                    "alice",
                    UUID.randomUUID().toString())
                .http())
        .isEqualTo(201);
    assertThat(balance("A")).isEqualByComparingTo("490");
  }

  @Test
  void rateLimitDoesNotMoveMoney() throws Exception {
    var controller =
        new com.example.payments.api.PaymentController(
            new com.example.payments.api.MessageParser(65536, "10000", 300, 2592000),
            service,
            new com.example.payments.api.ApiErrors(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            65536,
            1,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setMethod("GET");
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "alice", "unused");
    assertThat(controller.balance("A", auth, request).getStatusCode().value()).isEqualTo(200);
    assertThatThrownBy(() -> controller.balance("A", auth, request)).hasMessage("RATE_LIMITED");
    assertThat(count("payments")).isZero();
  }
}
