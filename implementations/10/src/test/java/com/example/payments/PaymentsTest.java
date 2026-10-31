package com.example.payments;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageCodec;
import com.example.payments.application.*;
import com.example.payments.domain.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(PostgresResource.class)
class PaymentsTest {
  @Inject Settings settings;
  @Inject DataSource db;
  @Inject MessageCodec codec;
  @Inject PaymentService service;
  @Inject FailureInjector faults;
  String id, source, dest, date;

  @BeforeEach
  void setup() throws Exception {
    id = UUID.randomUUID().toString();
    source = "S-" + id;
    dest = "D-" + id;
    date = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString();
    faults.point = "";
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO customers(id,display_name,status)"
              + " VALUES('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE') ON CONFLICT DO NOTHING");
      s.execute(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES('"
              + source
              + "','alice',500,500,'ACTIVE'),('"
              + dest
              + "','bob',100,100,'ACTIVE')");
    }
  }

  String token(String who, String scope) {
    return Jwt.issuer("https://test.local")
        .subject(who)
        .audience("payments")
        .claim("scope", scope)
        .expiresIn(300)
        .sign("privateKey.pem");
  }

  String token() {
    return token("alice", "payments:write payments:read accounts:read payments:admin");
  }

  String json(String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\""
        + source
        + "\",\"toAccount\":\""
        + dest
        + "\",\"amount\":"
        + amount
        + ",\"date\":\""
        + date
        + "\",\"purpose\":\" Invoice \"}";
  }

  String xml(String amount) {
    return "<payment><paymentId>"
        + id
        + "</paymentId><fromAccount>"
        + source
        + "</fromAccount><toAccount>"
        + dest
        + "</toAccount><amount>"
        + amount
        + "</amount><date>"
        + date
        + "</date><purpose>Invoice</purpose></payment>";
  }

  Command command(String amount) {
    return codec.decode(json(amount).getBytes(), "application/json");
  }

  PaymentService.Result transfer(String amount) {
    return service.transfer(command(amount), "alice", UUID.randomUUID().toString());
  }

  BigDecimal balance(String account) throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement p =
            c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
      p.setString(1, account);
      try (ResultSet r = p.executeQuery()) {
        r.next();
        return r.getBigDecimal(1);
      }
    }
  }

  long count(String table) throws Exception {
    try (Connection c = db.getConnection();
        PreparedStatement p =
            c.prepareStatement("SELECT count(*) FROM " + table + " WHERE payment_id=?")) {
      p.setObject(1, UUID.fromString(id));
      try (ResultSet r = p.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  void sql(String query) throws Exception {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      s.execute(query);
    }
  }

  @Test
  void jsonSuccessAndLedger() throws Exception {
    given()
        .auth()
        .oauth2(token())
        .contentType("application/json")
        .body(json("125.750"))
        .post("/api/v1/payments")
        .then()
        .statusCode(201)
        .header("Location", endsWith(id))
        .body("amount", equalTo("125.750"))
        .body("status", equalTo("COMPLETED"));
    assertEquals(new BigDecimal("374.250"), balance(source));
    assertEquals(new BigDecimal("225.750"), balance(dest));
    assertEquals(2, count("ledger_entries"));
    assertTrue(service.reconcile().stream().noneMatch(r -> r.get("identifier").equals(id)));
  }

  @Test
  void xmlAndCrossFormatReplay() throws Exception {
    var receipt =
        given()
            .auth()
            .oauth2(token())
            .contentType("application/xml")
            .accept("application/xml")
            .body(xml("125.750"))
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .extract()
            .asString();
    assertTrue(receipt.contains("125.750"));
    given()
        .auth()
        .oauth2(token())
        .contentType("application/json")
        .body(json("125.75"))
        .post("/api/v1/payments")
        .then()
        .statusCode(200);
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void invalidFieldsCollected() {
    Problem p =
        assertThrows(
            Problem.class,
            () ->
                codec.decode(
                    json("-1.0000")
                        .replace(date, "2020-01-01T00:00:00Z")
                        .replace(" Invoice ", " ")
                        .getBytes(),
                    "application/json"));
    assertEquals("VALIDATION_FAILED", p.code);
    assertTrue(p.issues.size() >= 3);
  }

  @Test
  void parserRejectsDuplicatesUnknownAndTypes() {
    for (String bad :
        List.of(
            json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
            json("true"),
            json("1").replace("\"purpose\"", "\"unknown\""),
            "[]",
            "{}{}")) {
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(Problem.class, () -> codec.decode(bad.getBytes(), "application/json")).code);
    }
  }

  @Test
  void xmlHardening() {
    for (String bad :
        List.of(
            "<!DOCTYPE payment [<!ENTITY xxe SYSTEM"
                + " 'file:///etc/passwd'>]><payment><purpose>&xxe;</purpose></payment>",
            xml("1").replace("</payment>", "<amount>2</amount></payment>"),
            "<payment><purpose><x/></purpose></payment>",
            "<payment xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include"
                + " href='file:///etc/passwd'/></payment>")) {
      assertThrows(Problem.class, () -> codec.decode(bad.getBytes(), "application/xml"));
    }
  }

  @Test
  void businessRejectionDurable() throws Exception {
    var r = transfer("600");
    assertEquals(409, r.http());
    assertEquals("INSUFFICIENT_FUNDS", r.body().get("code"));
    sql(
        "UPDATE accounts SET available_balance=1000,opening_balance=1000 WHERE id='"
            + source
            + "'");
    assertEquals(r.body(), transfer("600").body());
    assertEquals(0, count("ledger_entries"));
  }

  @Test
  void forbiddenAndLookupPrivacy() {
    assertEquals(403, service.transfer(command("1"), "bob", UUID.randomUUID().toString()).http());
    given()
        .auth()
        .oauth2(token("bob", "payments:read"))
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200);
    given()
        .auth()
        .oauth2(token("bob", "accounts:read"))
        .get("/api/v1/accounts/" + source + "/balance")
        .then()
        .statusCode(404);
  }

  @Test
  void authenticationAndScopes() {
    given()
        .contentType("application/json")
        .body(json("1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(401);
    given()
        .auth()
        .oauth2(token("alice", "accounts:read"))
        .contentType("application/json")
        .body(json("1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(403);
    String bad =
        Jwt.issuer("https://wrong.local")
            .subject("alice")
            .audience("payments")
            .expiresIn(300)
            .sign("privateKey.pem");
    given()
        .auth()
        .oauth2(bad)
        .contentType("application/json")
        .body(json("1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(401);
  }

  @Test
  void frozenAndUnknown() throws Exception {
    sql("UPDATE accounts SET status='FROZEN' WHERE id='" + dest + "'");
    assertEquals("ACCOUNT_NOT_ACTIVE", transfer("1").body().get("code"));
    id = UUID.randomUUID().toString();
    dest = "missing";
    assertEquals("FORBIDDEN", transfer("1").body().get("code"));
  }

  @Test
  void idempotencyConflict() throws Exception {
    var first = transfer("1");
    assertEquals(first.body(), transfer("1.000").body());
    assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(Problem.class, () -> transfer("2")).code);
    assertEquals(new BigDecimal("499.000"), balance(source));
  }

  @Test
  void duplicateRace() throws Exception {
    Command cmd = command("125.750");
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<PaymentService.Result>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(() -> service.transfer(cmd, "alice", UUID.randomUUID().toString())));
      int first = 0;
      for (var f : futures) {
        var result = f.get(20, TimeUnit.SECONDS);
        if (result.http() == 201) first++;
        else assertEquals(200, result.http());
      }
      assertEquals(1, first);
    }
    assertEquals(new BigDecimal("374.250"), balance(source));
    assertEquals(2, count("ledger_entries"));
  }

  @Test
  void competingDebitsCannotOverdraw() throws Exception {
    List<Command> cmds = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      id = UUID.randomUUID().toString();
      cmds.add(command("125"));
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      var fs =
          cmds.stream()
              .map(
                  c ->
                      pool.submit(() -> service.transfer(c, "alice", UUID.randomUUID().toString())))
              .toList();
      long complete = 0;
      for (var f : fs) if (f.get(20, TimeUnit.SECONDS).http() == 201) complete++;
      assertEquals(4, complete);
    }
    assertEquals(new BigDecimal("0.000"), balance(source));
    assertEquals(new BigDecimal("600.000"), balance(dest));
  }

  @Test
  void rollbackAtEveryMutation() throws Exception {
    for (String point : List.of("AFTER_DEBIT", "AFTER_CREDIT", "AFTER_LEDGER")) {
      faults.point = point;
      assertEquals(500, assertThrows(Problem.class, () -> transfer("1")).http);
      assertEquals(new BigDecimal("500.000"), balance(source));
      assertEquals(new BigDecimal("100.000"), balance(dest));
      assertEquals(0, count("payments"));
      assertEquals(0, count("ledger_entries"));
    }
    faults.point = "";
    assertEquals(201, transfer("1").http());
  }

  @Test
  void lostResponseReplayAndPersistence() throws Exception {
    faults.point = "AFTER_COMMIT";
    assertEquals(503, assertThrows(Problem.class, () -> transfer("1")).http);
    faults.point = "";
    assertEquals(200, transfer("1").http());
    assertEquals(new BigDecimal("499.000"), balance(source));
    given()
        .auth()
        .oauth2(token())
        .get("/api/v1/payments/" + id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"));
  }

  @Test
  void mediaNegotiation() {
    given()
        .auth()
        .oauth2(token())
        .contentType("text/plain")
        .body("x")
        .post("/api/v1/payments")
        .then()
        .statusCode(415);
    given()
        .auth()
        .oauth2(token())
        .accept("text/csv")
        .contentType("application/json")
        .body(json("1"))
        .post("/api/v1/payments")
        .then()
        .statusCode(406);
  }

  @Test
  void reconciliationDetectsCorruptFixture() throws Exception {
    sql("UPDATE accounts SET available_balance=501 WHERE id='" + source + "'");
    assertTrue(service.reconcile().stream().anyMatch(r -> source.equals(r.get("identifier"))));
    sql("UPDATE accounts SET available_balance=500 WHERE id='" + source + "'");
  }

  @Test
  void ledgerAndFinalPaymentsImmutable() throws Exception {
    transfer("1");
    assertThrows(
        SQLException.class,
        () -> sql("UPDATE ledger_entries SET signed_amount=0 WHERE payment_id='" + id + "'"));
    assertThrows(
        SQLException.class,
        () -> sql("UPDATE payments SET purpose='tampered' WHERE payment_id='" + id + "'"));
  }

  @Test
  void rateLimitAndBodyLimit() {
    int old = settings.rate;
    settings.rate = 1;
    String who = "rate-" + UUID.randomUUID();
    try {
      given()
          .auth()
          .oauth2(token(who, "payments:write"))
          .contentType("application/json")
          .body(json("1"))
          .post("/api/v1/payments")
          .then()
          .statusCode(403);
      given()
          .auth()
          .oauth2(token(who, "payments:write"))
          .contentType("application/json")
          .body(json("1"))
          .post("/api/v1/payments")
          .then()
          .statusCode(429)
          .header("Retry-After", notNullValue());
    } finally {
      settings.rate = old;
    }
    given()
        .auth()
        .oauth2(token())
        .contentType("application/json")
        .body(" ".repeat(70000))
        .post("/api/v1/payments")
        .then()
        .statusCode(413);
  }

  @Test
  void jwtAudienceAndExpiry() {
    for (String bad :
        List.of(
            Jwt.issuer("https://test.local")
                .subject("alice")
                .audience("wrong")
                .expiresIn(300)
                .sign("privateKey.pem"),
            Jwt.issuer("https://test.local")
                .subject("alice")
                .audience("payments")
                .issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().minusSeconds(300))
                .sign("privateKey.pem"))) {
      given()
          .auth()
          .oauth2(bad)
          .contentType("application/json")
          .body(json("1"))
          .post("/api/v1/payments")
          .then()
          .statusCode(401);
    }
  }

  @Test
  void boundedLockFailureRollsBack() throws Exception {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT * FROM accounts WHERE id='" + source + "' FOR UPDATE");
      long start = System.nanoTime();
      assertEquals(503, assertThrows(Problem.class, () -> transfer("1")).http);
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15);
      c.rollback();
    }
    assertEquals(0, count("payments"));
    assertEquals(new BigDecimal("500.000"), balance(source));
  }

  @Test
  void replaySurvivesPolicyWindowChanges() throws Exception {
    String original = json("125.750");
    given()
        .auth()
        .oauth2(token())
        .contentType("application/json")
        .body(original)
        .post("/api/v1/payments")
        .then()
        .statusCode(201);
    long oldAge = settings.maxAge;
    BigDecimal oldMax = settings.maxAmount;
    settings.maxAge = 0;
    settings.maxAmount = new BigDecimal("1");
    try {
      given()
          .auth()
          .oauth2(token())
          .contentType("application/json")
          .body(original)
          .post("/api/v1/payments")
          .then()
          .statusCode(200);
      given()
          .auth()
          .oauth2(token())
          .contentType("application/json")
          .body(original.replace(id, UUID.randomUUID().toString()))
          .post("/api/v1/payments")
          .then()
          .statusCode(400);
      assertEquals(new BigDecimal("374.250"), balance(source));
    } finally {
      settings.maxAge = oldAge;
      settings.maxAmount = oldMax;
    }
  }

  @Test
  void healthy() {
    given().get("/q/health/ready").then().statusCode(200);
    given().get("/q/health/live").then().statusCode(200);
  }
}
