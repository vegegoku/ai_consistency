package com.example.payments;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentIntegrationTest.FaultConfig.class)
class PaymentIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("payments.require-https",()->false);}
    @Autowired PaymentService service; @Autowired JdbcTemplate jdbc; @Autowired MockMvc http; @Autowired TestFault fault;
    @TestConfiguration static class FaultConfig { @Bean @Primary TestFault testFault(){return new TestFault();} }
    static class TestFault implements TransferFault { final AtomicBoolean fail=new AtomicBoolean(); public void afterDebit(){if(fail.getAndSet(false))throw new IllegalStateException("Injected rollback");} }
    @BeforeEach void seed(){
        jdbc.execute("TRUNCATE ledger_entries,payments,account_authorizations,accounts,customers RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
        jdbc.update("INSERT INTO accounts(id,customer_id,opening_balance,available_balance) VALUES ('ACC-1001','alice',500.000,500.000),('ACC-2002','bob',100.000,100.000)");
    }
    PaymentCommand command(UUID id,String amount){return new PaymentCommand(id,"ACC-1001","ACC-2002",new BigDecimal(amount).setScale(3),Instant.now(),"Invoice",null);}
    BigDecimal balance(String account){return jdbc.queryForObject("SELECT available_balance FROM accounts WHERE id=?",BigDecimal.class,account);}
    @Test void transfersReplaysConflictsAndRetrievesDurably(){
        var c=command(UUID.randomUUID(),"125.750");var first=service.transfer(c,"alice","test-correlation");assertEquals(201,first.http());
        assertEquals(new BigDecimal("374.250"),balance("ACC-1001")); assertEquals(new BigDecimal("225.750"),balance("ACC-2002"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
        var second=service.transfer(c,"alice","different");assertEquals(200,second.http());assertEquals(first.body(),second.body());
        assertEquals(first.body(),service.find(c.paymentId(),"alice").body());
        var changed=new PaymentCommand(c.paymentId(),c.fromAccount(),c.toAccount(),new BigDecimal("1.000"),c.date(),c.purpose(),null);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ApiFailure.class,()->service.transfer(changed,"alice","c")).code());
        assertEquals("NOT_FOUND",assertThrows(ApiFailure.class,()->service.find(c.paymentId(),"mallory")).code());
        assertTrue(service.reconcile().isEmpty());
    }
    @Test void storesRejectionsAndEnforcesAuthorizationAndRestrictions(){
        var c=command(UUID.randomUUID(),"501.000");var rejected=service.transfer(c,"alice","c");assertEquals(409,rejected.http());assertEquals("INSUFFICIENT_FUNDS",rejected.body().get("code"));assertEquals(rejected.body(),service.transfer(c,"alice","x").body());
        assertEquals(403,service.transfer(command(UUID.randomUUID(),"1"),"mallory","c").http());
        jdbc.update("UPDATE accounts SET status='FROZEN' WHERE id='ACC-2002'");
        assertEquals("ACCOUNT_NOT_ACTIVE",service.transfer(command(UUID.randomUUID(),"1"),"alice","c").body().get("code"));
        assertEquals(new BigDecimal("500.000"),balance("ACC-1001"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
    }
    @Test void duplicateRaceHasOneFinancialEffect() throws Exception {
        var c=command(UUID.randomUUID(),"125.750");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<PaymentService.Outcome>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->{start.await();return service.transfer(c,"alice","c");}));start.countDown();
            int created=0;for(var f:tasks){var result=f.get(20,TimeUnit.SECONDS);if(result.http()==201)created++;else assertEquals(200,result.http());}assertEquals(1,created);
        }
        assertEquals(new BigDecimal("374.250"),balance("ACC-1001"));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
    }
    @Test void concurrentDebitsCannotOverdraw() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<PaymentService.Outcome>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->service.transfer(command(UUID.randomUUID(),"100"),"alice","c")));
            int completed=0;for(var f:tasks)if(f.get(20,TimeUnit.SECONDS).http()==201)completed++;assertEquals(5,completed);
        }
        assertEquals(new BigDecimal("0.000"),balance("ACC-1001"));assertEquals(new BigDecimal("600.000"),balance("ACC-2002"));assertTrue(service.reconcile().isEmpty());
    }
    @Test void failureAfterDebitRollsBackEverything(){
        var c=command(UUID.randomUUID(),"125.750");fault.fail.set(true);assertThrows(IllegalStateException.class,()->service.transfer(c,"alice","c"));
        assertEquals(new BigDecimal("500.000"),balance("ACC-1001"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM payments",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
        assertEquals(201,service.transfer(c,"alice","c").http());
    }
    @Test void immutableLedgerAndReconciliation(){
        service.transfer(command(UUID.randomUUID(),"1"),"alice","c");assertThrows(Exception.class,()->jdbc.update("UPDATE ledger_entries SET signed_amount=1"));
        jdbc.update("UPDATE accounts SET available_balance=available_balance+1 WHERE id='ACC-1001'");assertFalse(service.reconcile().isEmpty());
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_immutable");
        try{jdbc.update("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");assertTrue(service.reconcile().stream().anyMatch(m->m.get("kind").equals("PAYMENT_LEDGER")));}
        finally{jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_immutable");}
    }
    String json(UUID id){return "{\"paymentId\":\""+id+"\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\""+Instant.now()+"\",\"purpose\":\"Invoice\"}";}
    @Test void apiJsonXmlAuthAndNegotiation() throws Exception {
        var auth=jwt().jwt(j->j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_payments:write"),new SimpleGrantedAuthority("SCOPE_payments:read"));
        UUID id=UUID.randomUUID();String request=json(id);
        http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(request)).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("125.750")).andExpect(header().exists("X-Correlation-ID"));
        http.perform(get("/api/v1/payments/"+id).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        http.perform(get("/api/v1/accounts/ACC-1001/balance").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value("374.250"));
        http.perform(get("/api/v1/accounts/ACC-2002/balance").with(auth)).andExpect(status().isNotFound());
        http.perform(post("/api/v1/payments").contentType("application/json").content(request)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        http.perform(post("/api/v1/payments").with(jwt().jwt(j->j.subject("alice"))).contentType("application/json").content(request)).andExpect(status().isForbidden());
        http.perform(post("/api/v1/payments").with(auth).contentType("text/plain").content(request)).andExpect(status().isUnsupportedMediaType());
        http.perform(post("/api/v1/payments").with(auth).accept("text/html").contentType("application/json").content(request)).andExpect(status().isNotAcceptable());
        String xml="<payment><paymentId>"+UUID.randomUUID()+"</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.000</amount><date>"+Instant.now()+"</date><purpose>XML</purpose></payment>";
        http.perform(post("/api/v1/payments").with(auth).contentType("application/xml").accept("application/xml").content(xml)).andExpect(status().isCreated()).andExpect(content().contentTypeCompatibleWith("application/xml")).andExpect(xpath("/receipt/amount").string("1.000"));
    }
    @Test void malformedAndOversizedRequestsDoNotMoveMoney() throws Exception {
        var auth=jwt().jwt(j->j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));
        for(String invalid:List.of("{}","{","{\"amount\":1,\"amount\":2}"))http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(" ".repeat(65537))).andExpect(status().isPayloadTooLarge());
        assertEquals(new BigDecimal("500.000"),balance("ACC-1001"));
    }
    @Test void missingClosedAndPolicyRestrictedAccountsAreSafe(){
        var base=command(UUID.randomUUID(),"1");
        var missing=new PaymentCommand(base.paymentId(),base.fromAccount(),"MISSING",base.amount(),base.date(),base.purpose(),null);
        assertEquals("FORBIDDEN",service.transfer(missing,"alice","c").body().get("code"));
        jdbc.update("UPDATE accounts SET status='CLOSED' WHERE id='ACC-2002'");assertEquals("ACCOUNT_NOT_ACTIVE",service.transfer(command(UUID.randomUUID(),"1"),"alice","c").body().get("code"));
        jdbc.update("UPDATE accounts SET status='ACTIVE',receive_enabled=false WHERE id='ACC-2002'");assertEquals("FORBIDDEN",service.transfer(command(UUID.randomUUID(),"1"),"alice","c").body().get("code"));
        jdbc.update("UPDATE accounts SET receive_enabled=true WHERE id='ACC-2002'");jdbc.update("UPDATE accounts SET max_single_payment=10,daily_limit=15 WHERE id='ACC-1001'");
        assertEquals("PAYMENT_LIMIT_EXCEEDED",service.transfer(command(UUID.randomUUID(),"11"),"alice","c").body().get("code"));assertEquals(201,service.transfer(command(UUID.randomUUID(),"10"),"alice","c").http());assertEquals("PAYMENT_LIMIT_EXCEEDED",service.transfer(command(UUID.randomUUID(),"6"),"alice","c").body().get("code"));
    }
    @Test void explicitDelegateCanDebitAndView(){
        jdbc.update("INSERT INTO account_authorizations(account_id,principal,can_debit,can_view) VALUES ('ACC-1001','delegate',true,true)");
        assertEquals(201,service.transfer(command(UUID.randomUUID(),"1"),"delegate","c").http());assertEquals("499.000",service.balance("ACC-1001","delegate").get("balance"));
    }
    @Test void boundedLockTimeoutLeavesNoFinancialChanges() throws Exception {
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
            connection.setAutoCommit(false);try(var statement=connection.createStatement()){statement.execute("SELECT * FROM accounts WHERE id='ACC-1001' FOR UPDATE");}
            var c=command(UUID.randomUUID(),"1");long started=System.nanoTime();var failure=assertThrows(ApiFailure.class,()->service.transfer(c,"alice","c"));assertEquals("TEMPORARILY_UNAVAILABLE",failure.code());assertTrue(System.nanoTime()-started<java.time.Duration.ofSeconds(10).toNanos());connection.rollback();
        }
        assertEquals(new BigDecimal("500.000"),balance("ACC-1001"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM payments",Integer.class));
    }
    @Test void reconstructedServiceRecoversCommittedState(){
        var c=command(UUID.randomUUID(),"125.750");var receipt=service.transfer(c,"alice","lost-response");
        var fresh=new PaymentService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),java.time.Clock.systemUTC(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),()->{},3);
        assertEquals(receipt.body(),fresh.find(c.paymentId(),"alice").body());assertEquals(receipt.body(),fresh.transfer(c,"alice","retry").body());assertEquals(new BigDecimal("374.250"),balance("ACC-1001"));
    }
    @Test void jsonAndXmlRetryShareOneFinancialEffect() throws Exception {
        var auth=jwt().jwt(j->j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));UUID id=UUID.randomUUID();Instant date=Instant.now();
        String request=json(id);var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(request);String timestamp=tree.get("date").asText();
        http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(request)).andExpect(status().isCreated());
        String xml="<payment><paymentId>"+id+"</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>"+timestamp+"</date><purpose>Invoice</purpose></payment>";
        http.perform(post("/api/v1/payments").with(auth).contentType("application/xml").content(xml)).andExpect(status().isOk());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Integer.class));
    }

    @Test void historicalRetryReturnsOriginalButNewOldInstructionFails() throws Exception {
        Instant old=Instant.now().minus(java.time.Duration.ofDays(31));UUID id=UUID.randomUUID();var c=new PaymentCommand(id,"ACC-1001","ACC-2002",new BigDecimal("1.000"),old,"historical",null);
        var original=new PaymentService(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),java.time.Clock.fixed(old,java.time.ZoneOffset.UTC),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),()->{},3);
        assertEquals(201,original.transfer(c,"alice","historical").http());
        var auth=jwt().jwt(j->j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_payments:write"));
        String payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("paymentId",id.toString(),"fromAccount","ACC-1001","toAccount","ACC-2002","amount","1.000","date",old.toString(),"purpose","historical"));
        http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(payload)).andExpect(status().isOk());
        http.perform(post("/api/v1/payments").with(auth).contentType("application/json").content(payload.replace(id.toString(),UUID.randomUUID().toString()))).andExpect(status().isBadRequest());
    }

}
