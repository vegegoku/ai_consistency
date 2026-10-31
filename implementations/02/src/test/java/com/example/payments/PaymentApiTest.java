package com.example.payments;

import com.example.payments.api.MessageCodec;
import com.example.payments.application.*;
import com.example.payments.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentApiTest {
    @Inject DataSource database;
    @Inject MessageCodec codec;
    @Inject PaymentService service;
    @Inject ReconciliationService reconciliation;
    String alice;
    String bob;
    String mallory;
    @BeforeEach void fixtures() throws Exception {
        FailingProbe.FAIL.set(false);
        try(Connection c=database.getConnection();Statement s=c.createStatement()) {
            s.execute("TRUNCATE payment_issues,ledger_entries,payments,account_permissions,accounts,customers RESTART IDENTITY CASCADE");
            s.execute("INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
            s.execute("INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status) VALUES ('ACC-1001','alice','JOD',500,500,'ACTIVE'),('ACC-2002','bob','JOD',100,100,'ACTIVE'),('ACC-3003','alice','JOD',0,0,'ACTIVE')");
        }
        alice=token("alice","payments:submit payments:read payments:admin");bob=token("bob","payments:submit payments:read");mallory=token("mallory","payments:submit payments:read");
    }
    String token(String sub,String scopes) {return Jwt.issuer("https://test-issuer.example").subject(sub).audience("payments-api").expiresIn(600).claim("scope",scopes).sign();}
    String instruction(String id,String amount) {return "{\"paymentId\":\""+id+"\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"+amount+",\"date\":\""+OffsetDateTime.now(ZoneOffset.UTC)+"\",\"purpose\":\" Invoice \"}";}
    Response post(String body,String token){return given().auth().oauth2(token).contentType("application/json").body(body).post("/api/v1/payments");}
    long count(String table) throws Exception {try(Connection c=database.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}}
    String balance(String account) throws Exception {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")){s.setString(1,account);try(ResultSet r=s.executeQuery()){r.next();return r.getBigDecimal(1).toPlainString();}}}
    void sql(String query) throws Exception {try(Connection c=database.getConnection();Statement s=c.createStatement()){s.execute(query);}}
    @Test @Order(1) void jsonSuccessHasExactAccountingAndDurableReceipt() throws Exception {
        String id=UUID.randomUUID().toString();
        var r=post(instruction(id,"125.750"),alice);
        r.then().statusCode(201).header("Location",endsWith("/api/v1/payments/"+id)).header("X-Correlation-ID",notNullValue()).body("amount",equalTo("125.750")).body("currency",equalTo("JOD")).body("status",equalTo("COMPLETED"));
        assertEquals("374.250",balance("ACC-1001"));assertEquals("225.750",balance("ACC-2002"));assertEquals(1,count("payments"));assertEquals(2,count("ledger_entries"));
        given().auth().oauth2(alice).get("/api/v1/payments/"+id).then().statusCode(200).body("processedAt",equalTo(r.jsonPath().getString("processedAt")));
        assertEquals(true,reconciliation.report().get("healthy"));
        try(Connection c=database.getConnection();Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT requested_by,correlation_id FROM payments")){assertTrue(rows.next());assertEquals("alice",rows.getString(1));assertNotNull(rows.getObject(2));}
    }
    @Test void xmlInputProducesSameAccountingAndCanonicalXmlReceipt() throws Exception {
        String id=UUID.randomUUID().toString();
        String xml="<payment><paymentId>"+id+"</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>"+OffsetDateTime.now(ZoneOffset.UTC)+"</date><purpose>Invoice</purpose></payment>";
        given().auth().oauth2(alice).contentType("application/xml").accept("application/xml").body(xml).post("/api/v1/payments").then().statusCode(201).contentType("application/xml").body("response.amount",equalTo("125.750"));
        assertEquals("374.250",balance("ACC-1001"));assertEquals("225.750",balance("ACC-2002"));
    }
    @Test void identicalRetryAcrossFormatsReturnsOriginalReceiptOnce() throws Exception {
        String id=UUID.randomUUID().toString(),body=instruction(id,"125.750");
        Response first=post(body,alice);first.then().statusCode(201);
        var cmd=codec.parse(body.getBytes(),"application/json");
        String xml="<payment><paymentId>"+id+"</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>"+cmd.date()+"</date><purpose>Invoice</purpose></payment>";
        given().auth().oauth2(alice).contentType("application/xml").body(xml).post("/api/v1/payments").then().statusCode(200).body(equalTo(first.asString()));
        post(body.replace("125.750","126.750"),alice).then().statusCode(409).body("code",equalTo("IDEMPOTENCY_CONFLICT"));
        assertEquals(2,count("ledger_entries"));assertEquals("374.250",balance("ACC-1001"));
    }
    @Test void malformedAndInvalidFieldsCannotMoveBalances() throws Exception {
        for(String bad:new String[]{"{",instruction(UUID.randomUUID().toString(),"true"),instruction(UUID.randomUUID().toString(),"1").replace("\"purpose\"","\"unknown\"")})post(bad,alice).then().statusCode(400).body("code",equalTo("MALFORMED_MESSAGE"));
        post("{\"paymentId\":\"bad\",\"amount\":0.0001}",alice).then().statusCode(400).body("code",equalTo("VALIDATION_FAILED")).body("issues.size()",greaterThanOrEqualTo(6)).body("paymentId",nullValue());
        assertEquals(0,count("payments"));assertEquals("500.000",balance("ACC-1001"));
    }
    @Test void businessRejectionsAreDurableAndReplayTheirOriginalOutcome() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"600.000");
        post(body,alice).then().statusCode(409).body("code",equalTo("INSUFFICIENT_FUNDS"));
        sql("UPDATE accounts SET available_balance=1000 WHERE id='ACC-1001'");
        post(body,alice).then().statusCode(409).body("code",equalTo("INSUFFICIENT_FUNDS"));
        assertEquals(1,count("payments"));assertEquals(1,count("payment_issues"));assertEquals(0,count("ledger_entries"));assertEquals("1000.000",balance("ACC-1001"));
        String id=codec.parse(body.getBytes(),"application/json").paymentId();
        given().auth().oauth2(alice).get("/api/v1/payments/"+id).then().statusCode(200).body("status",equalTo("REJECTED"));
    }
    @Test void frozenClosedMissingAndRestrictedAccountsNeverMoveFunds() throws Exception {
        for(String state:List.of("FROZEN","CLOSED")) {
            sql("UPDATE accounts SET status='"+state+"' WHERE id='ACC-2002'");
            post(instruction(UUID.randomUUID().toString(),"1"),alice).then().statusCode(409).body("code",equalTo("ACCOUNT_NOT_ACTIVE"));
        }
        sql("UPDATE accounts SET status='ACTIVE',receive_policy='ALLOWLIST' WHERE id='ACC-2002'");
        post(instruction(UUID.randomUUID().toString(),"1"),alice).then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        post(instruction(UUID.randomUUID().toString(),"1").replace("ACC-2002","UNKNOWN"),alice).then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        assertEquals("500.000",balance("ACC-1001"));assertEquals(0,count("ledger_entries"));
    }
    @Test void explicitPermissionsAndAccountLimitsAreEnforced() throws Exception {
        sql("INSERT INTO account_permissions(account_id,principal,can_debit) VALUES ('ACC-1001','bob',true)");
        sql("UPDATE accounts SET max_transfer_amount=20,daily_outgoing_limit=25 WHERE id='ACC-1001'");
        post(instruction(UUID.randomUUID().toString(),"21"),bob).then().statusCode(409).body("code",equalTo("PAYMENT_LIMIT_EXCEEDED"));
        post(instruction(UUID.randomUUID().toString(),"20"),bob).then().statusCode(201);
        post(instruction(UUID.randomUUID().toString(),"6"),bob).then().statusCode(409).body("code",equalTo("PAYMENT_LIMIT_EXCEEDED"));
        assertEquals("480.000",balance("ACC-1001"));
    }
    @Test void authorizationAndLookupNonDisclosure() throws Exception {
        String id=UUID.randomUUID().toString();
        post(instruction(id,"1"),mallory).then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        given().auth().oauth2(mallory).get("/api/v1/accounts/ACC-1001/balance").then().statusCode(404);
        String good=UUID.randomUUID().toString();post(instruction(good,"1"),alice).then().statusCode(201);
        given().auth().oauth2(mallory).get("/api/v1/payments/"+good).then().statusCode(404);
        given().auth().oauth2(bob).get("/api/v1/payments/"+good).then().statusCode(200);
        given().auth().oauth2(alice).get("/api/v1/accounts/ACC-1001/balance").then().statusCode(200).body("availableBalance",equalTo("499.000"));
        assertEquals(2,count("ledger_entries"));
    }
    @Test void bearerIssuerAudienceExpirySignatureAndScopesAreRequired() {
        given().contentType("application/json").body("{}").post("/api/v1/payments").then().statusCode(401).body("code",equalTo("UNAUTHENTICATED"));
        String badIssuer=Jwt.issuer("https://wrong").subject("alice").audience("payments-api").expiresIn(100).claim("scope","payments:submit").sign();
        String badAudience=Jwt.issuer("https://test-issuer.example").subject("alice").audience("wrong").expiresIn(100).claim("scope","payments:submit").sign();
        String expired=Jwt.issuer("https://test-issuer.example").subject("alice").audience("payments-api").issuedAt(Instant.now().minusSeconds(100)).expiresAt(Instant.now().minusSeconds(5)).claim("scope","payments:submit").sign();
        for(String invalid:new String[]{"garbage",badIssuer,badAudience,expired,alice.substring(0,alice.lastIndexOf('.')+1)+"AAAA"})post("{}",invalid).then().statusCode(401).body("code",equalTo("UNAUTHENTICATED"));
        post("{}",token("alice","payments:read")).then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        given().auth().oauth2(bob).get("/api/v1/admin/reconciliation").then().statusCode(403);
        given().header("Authorization","bearer "+alice).contentType("application/json").body(instruction(UUID.randomUUID().toString(),"1")).post("/api/v1/payments").then().statusCode(201);
    }
    @Test void mediaNegotiationRejectsBeforeExecutionAndXmlRejectsXxe() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"1");
        given().auth().oauth2(alice).contentType("text/plain").body(body).post("/api/v1/payments").then().statusCode(415).body("code",equalTo("UNSUPPORTED_MEDIA_TYPE"));
        given().auth().oauth2(alice).contentType("application/json").accept("text/plain").body(body).post("/api/v1/payments").then().statusCode(406);
        given().auth().oauth2(alice).contentType("application/xml").body("<!DOCTYPE payment [<!ENTITY x SYSTEM 'http://127.0.0.1:9/'>]><payment><purpose>&x;</purpose></payment>").post("/api/v1/payments").then().statusCode(400);
        given().auth().oauth2(alice).contentType("application/json").body(" ".repeat(65537)).post("/api/v1/payments").then().statusCode(413);
        assertEquals("500.000",balance("ACC-1001"));assertEquals(0,count("payments"));
    }
    @Test void failureAfterDebitRollsBackAllFinancialState() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"125.750");FailingProbe.FAIL.set(true);
        post(body,alice).then().statusCode(500).body("code",equalTo("INTERNAL_ERROR"));
        assertEquals("500.000",balance("ACC-1001"));assertEquals("100.000",balance("ACC-2002"));assertEquals(0,count("payments"));assertEquals(0,count("ledger_entries"));
        post(body,alice).then().statusCode(201);assertEquals("374.250",balance("ACC-1001"));
    }
    @Test void lostResponseAndRecreatedServiceDiscoverCommittedOutcome() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"125.750");var p=codec.parse(body.getBytes(),"application/json");
        var committed=service.transfer(p,"alice",UUID.randomUUID().toString()); // Drop original response deliberately.
        post(body,alice).then().statusCode(200).body("processedAt",equalTo(committed.payment().processedAt().toString()));
        assertEquals("374.250",balance("ACC-1001"));assertEquals(2,count("ledger_entries"));
        // A separate connection reads durable state, with no application memory used for idempotency.
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT status FROM payments WHERE payment_id=?")){s.setObject(1,UUID.fromString(p.paymentId()));try(ResultSet r=s.executeQuery()){assertTrue(r.next());assertEquals("COMPLETED",r.getString(1));}}
    }
    @Test void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"125.750");
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            CountDownLatch start=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();return post(body,alice).statusCode();}));start.countDown();
            List<Integer> statuses=new ArrayList<>();for(var f:results)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(x->x==201).count());assertEquals(7,statuses.stream().filter(x->x==200).count());
        }
        assertEquals("374.250",balance("ACC-1001"));assertEquals(2,count("ledger_entries"));
    }
    @Test void concurrentDebitsCannotOverdrawOrLoseMoney() throws Exception {
        try(ExecutorService pool=Executors.newFixedThreadPool(8)) {
            CountDownLatch start=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<8;i++){String b=instruction(UUID.randomUUID().toString(),"100");results.add(pool.submit(()->{start.await();return post(b,alice).statusCode();}));}start.countDown();
            List<Integer> statuses=new ArrayList<>();for(var f:results)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(5,statuses.stream().filter(x->x==201).count());assertEquals(3,statuses.stream().filter(x->x==409).count());
        }
        assertEquals("0.000",balance("ACC-1001"));assertEquals("600.000",balance("ACC-2002"));assertEquals(10,count("ledger_entries"));assertEquals(true,reconciliation.report().get("healthy"));
    }
    @Test void opposingTransfersUseConsistentLockOrdering() throws Exception {
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            String a=instruction(UUID.randomUUID().toString(),"10"),b=instruction(UUID.randomUUID().toString(),"20").replace("ACC-1001","TEMP").replace("ACC-2002","ACC-1001").replace("TEMP","ACC-2002");
            var one=pool.submit(()->post(a,alice).statusCode());var two=pool.submit(()->post(b,bob).statusCode());assertEquals(201,one.get());assertEquals(201,two.get());
        }
        assertEquals("510.000",balance("ACC-1001"));assertEquals("90.000",balance("ACC-2002"));assertEquals(true,reconciliation.report().get("healthy"));
    }
    @Test void lockTimeoutReturnsRetryableErrorWithoutPersistingRejection() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"1");
        try(Connection c=database.getConnection();Statement s=c.createStatement()) {
            c.setAutoCommit(false);s.execute("SELECT * FROM accounts WHERE id='ACC-1001' FOR UPDATE");
            post(body,alice).then().statusCode(503).header("Retry-After",equalTo("1")).body("code",equalTo("TEMPORARILY_UNAVAILABLE"));c.rollback();
        }
        assertEquals(0,count("payments"));assertEquals("500.000",balance("ACC-1001"));post(body,alice).then().statusCode(201);
    }
    @Test void ledgerAndPaymentUpdatesAreDatabaseRejectedAndCorruptionIsDetected() throws Exception {
        String id=UUID.randomUUID().toString();post(instruction(id,"1"),alice).then().statusCode(201);
        assertThrows(SQLException.class,()->sql("UPDATE ledger_entries SET signed_amount=999"));
        assertThrows(SQLException.class,()->sql("DELETE FROM payments WHERE payment_id='"+id+"'"));
        assertThrows(SQLException.class,()->sql("UPDATE accounts SET opening_balance=999 WHERE id='ACC-1001'"));
        sql("UPDATE accounts SET available_balance=42 WHERE id='ACC-1001'");
        assertEquals(false,reconciliation.report().get("healthy"));
        given().auth().oauth2(alice).get("/api/v1/admin/reconciliation").then().statusCode(200).body("healthy",equalTo(false)).body("issues[0].type",equalTo("ACCOUNT_BALANCE_MISMATCH"));
        // Superuser-only corruption fixture bypasses immutable triggers, as a restore incident might.
        sql("ALTER TABLE ledger_entries DISABLE TRIGGER USER");try {sql("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");}finally {sql("ALTER TABLE ledger_entries ENABLE TRIGGER USER");}
        assertTrue(reconciliation.report().get("issues").toString().contains("PAYMENT_LEDGER_MISMATCH"));
    }

    @Test void freshAmountAndInstructionWindowsAreValidatedTogether() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"10000.001");
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("date",OffsetDateTime.now(ZoneOffset.UTC).minusDays(31).toString());
        post(tree.toString(),alice).then().statusCode(400).body("code",equalTo("VALIDATION_FAILED")).body("issues.size()",equalTo(2));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("amount", "1").put("date",OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(6).toString());
        post(tree.toString(),alice).then().statusCode(400).body("issues[0].field",equalTo("date"));
        assertEquals(0,count("payments"));assertEquals("500.000",balance("ACC-1001"));
    }
    @Test void historicalRetryRemainsDiscoverableAfterInstructionWindowExpires() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"1");
        post(body,alice).then().statusCode(201);
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        node.put("date",OffsetDateTime.now(ZoneOffset.UTC).minusDays(40).toString());
        String historic=node.toString();var old=codec.parse(historic.getBytes(),"application/json");
        // Restore fixture represents a payment originally accepted forty days ago.
        sql("ALTER TABLE payments DISABLE TRIGGER USER");
        try(Connection c=database.getConnection();PreparedStatement q=c.prepareStatement("UPDATE payments SET instruction_date=?,payload_fingerprint=? WHERE payment_id=?")) {
            q.setTimestamp(1,Timestamp.from(old.date()));q.setString(2,old.fingerprint());q.setObject(3,UUID.fromString(old.paymentId()));q.executeUpdate();
        }finally {sql("ALTER TABLE payments ENABLE TRIGGER USER");}
        post(historic,alice).then().statusCode(200);assertEquals("499.000",balance("ACC-1001"));assertEquals(2,count("ledger_entries"));
    }
    @Test void principalRateQuotaIsBoundedAndRetryable() {
        String token=token("quota-only", "payments:read");int status=0;
        for(int i=0;i<402&&status!=429;i++) {
            status=given().auth().oauth2(token).get("/api/v1/accounts/ACC-1001/balance").statusCode();
            assertTrue(status==404||status==429);
        }
        assertEquals(429,status);
        given().auth().oauth2(token).get("/api/v1/accounts/ACC-1001/balance").then().statusCode(429).header("Retry-After",equalTo("1")).body("code",equalTo("RATE_LIMITED"));
    }

    @Test void veryLargeRequestStillUsesTheStandardErrorContract() {
        given().auth().oauth2(alice).contentType("application/json").body(" ".repeat(1024*1024)).post("/api/v1/payments").then().statusCode(413).body("code",equalTo("PAYLOAD_TOO_LARGE")).body("correlationId",notNullValue());
    }
    @Test void anotherPrincipalCannotReplayOrChangeAnExistingInstruction() throws Exception {
        String body=instruction(UUID.randomUUID().toString(),"1");post(body,alice).then().statusCode(201);
        post(body,bob).then().statusCode(403).body("code",equalTo("FORBIDDEN")).body("amount",nullValue());
        assertEquals(1,count("payments"));assertEquals(2,count("ledger_entries"));assertEquals("499.000",balance("ACC-1001"));
    }
    @Test void healthOpenApiAndMetricsAreExposed() {
        given().get("/q/health/live").then().statusCode(200);given().get("/q/health/ready").then().statusCode(200);
        given().get("/q/openapi").then().statusCode(200).body(containsString("/api/v1/payments"));
        given().get("/q/metrics").then().statusCode(200).body(containsString("payments_completed_total"));
    }
}
