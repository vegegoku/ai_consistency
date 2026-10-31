package com.example.payments;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class PaymentIntegrationTest {
    @Inject PaymentService service; @Inject DataSource db; @Inject ReconciliationService reconciliation;
    @BeforeEach void seed()throws Exception {
        try(var c=db.getConnection();var s=c.createStatement()) {
            s.execute("TRUNCATE payment_issues,ledger_entries,payments,account_grants,accounts,customers,rate_buckets CASCADE");
            s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob'),('mallory','Mallory')");
            s.execute("INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('A','alice',500.000,500.000),('B','bob',100.000,100.000),('C','alice',100.000,100.000)");
        }
    }
    PaymentCommand command(UUID id,String from,String to,String amount){return new PaymentCommand(id,from,to,new BigDecimal(amount).setScale(3),Instant.now(),"Invoice","");}
    PaymentCommand command(String amount){return command(UUID.randomUUID(),"A","B",amount);}
    String balance(String id)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement("select available_balance from accounts where id=?")){s.setString(1,id);try(var r=s.executeQuery()){r.next();return r.getBigDecimal(1).toPlainString();}}}
    long count(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("select count(*) from "+table)){r.next();return r.getLong(1);}}
    @Test void exactTransferReplayConflictAndReceiptPersistence()throws Exception {
        var cmd=command("125.750");var first=service.transfer(cmd,"alice",UUID.randomUUID().toString());assertTrue(first.created());assertEquals("COMPLETED",first.payment().status());
        assertEquals("374.250",balance("A"));assertEquals("225.750",balance("B"));assertEquals(2,count("ledger_entries"));
        var replay=service.transfer(cmd,"alice","new-correlation");assertFalse(replay.created());assertEquals(first.payment(),replay.payment());
        assertEquals(first.payment(),service.find(cmd.paymentId(),"alice"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->service.transfer(command(cmd.paymentId(),"A","B","1.000"),"alice","x")).code());
        assertEquals(2,count("ledger_entries"));assertTrue(reconciliation.report().clean());
    }
    @Test void rejectionDurableAndUnauthorizedReplayHidden()throws Exception {
        var cmd=command("501");var rejected=service.transfer(cmd,"alice","c");assertEquals("INSUFFICIENT_FUNDS",rejected.payment().rejectionCode());
        assertEquals(rejected.payment(),service.transfer(cmd,"alice","other").payment());assertEquals("500.000",balance("A"));assertEquals(0,count("ledger_entries"));
        assertEquals(403,assertThrows(PaymentException.class,()->service.transfer(command("1"),"mallory","c")).http());
        assertEquals(404,assertThrows(PaymentException.class,()->service.find(cmd.paymentId(),"mallory")).http());
    }
    @Test void inactiveUnknownAndPolicyRestrictionsMoveNoMoney()throws Exception {
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("update accounts set status='FROZEN' where id='B'");}
        assertEquals("ACCOUNT_NOT_ACTIVE",service.transfer(command("1"),"alice","c").payment().rejectionCode());
        assertEquals("FORBIDDEN",assertThrows(PaymentException.class,()->service.transfer(command(UUID.randomUUID(),"MISSING","B","1"),"alice","c")).code());
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("update accounts set status='ACTIVE',max_payment=0.500 where id='B'");s.execute("update accounts set max_payment=0.500 where id='A'");}
        assertEquals("PAYMENT_LIMIT_EXCEEDED",service.transfer(command("1"),"alice","c").payment().rejectionCode());
        assertEquals("500.000",balance("A"));
    }
    @Test void concurrentDuplicatesExactlyOnce()throws Exception {
        var cmd=command("125.750");var results=parallel(12,()->service.transfer(cmd,"alice","race"));
        assertEquals(1,results.stream().filter(TransferResult::created).count());assertEquals(1,count("payments"));assertEquals(2,count("ledger_entries"));assertEquals("374.250",balance("A"));
    }
    @Test void concurrentDebitsCannotOverdraw()throws Exception {
        var results=parallel(12,()->service.transfer(command("100"),"alice","race"));
        assertEquals(5,results.stream().filter(r->r.payment().status().equals("COMPLETED")).count());assertEquals("0.000",balance("A"));assertEquals("600.000",balance("B"));assertTrue(reconciliation.report().clean());
    }
    @Test void rollbackAfterDebitAndLedgerFailure()throws Exception {
        try(var c=db.getConnection();var s=c.createStatement()){
            s.execute("CREATE FUNCTION test_fail_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='B' THEN RAISE EXCEPTION 'injected credit fault'; END IF; RETURN NEW; END $$");
            s.execute("CREATE TRIGGER test_fail BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION test_fail_credit()");
        }
        try { assertThrows(PaymentException.class,()->service.transfer(command("10"),"alice","fault")); }
        finally {try(var c=db.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER test_fail ON accounts");s.execute("DROP FUNCTION test_fail_credit()");}}
        assertEquals("500.000",balance("A"));assertEquals("100.000",balance("B"));assertEquals(0,count("payments"));assertEquals(0,count("ledger_entries"));
    }
    @Test void reconciliationDetectsCorruptedBaseline()throws Exception {
        service.transfer(command("10"),"alice","c");
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("update accounts set available_balance=available_balance+1 where id='A'");}
        assertFalse(reconciliation.report().clean());assertEquals(1,reconciliation.report().balanceMismatches().size());
    }
    @Test void ledgerAndFinalPaymentsAreImmutable()throws Exception {
        service.transfer(command("10"),"alice","c");
        try(var c=db.getConnection();var s=c.createStatement()){
            assertThrows(SQLException.class,()->s.execute("delete from ledger_entries"));
            assertThrows(SQLException.class,()->s.execute("update payments set amount=1"));
        }
    }
    @Test void reverseDirectionTransfersUseConsistentLockOrdering()throws Exception {
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("INSERT INTO account_grants(account_id,principal,can_debit) VALUES('B','alice',true)");}
        var index=new java.util.concurrent.atomic.AtomicInteger();
        parallel(16,()->{boolean reverse=index.getAndIncrement()%2==0;return service.transfer(command(UUID.randomUUID(),reverse?"B":"A",reverse?"A":"B","1.000"),"alice","c");});
        assertEquals("500.000",balance("A"));assertEquals("100.000",balance("B"));assertEquals(32,count("ledger_entries"));assertTrue(reconciliation.report().clean());
    }
    @Test void ledgerInsertionFailureRollsBackBothBalances()throws Exception {
        try(var c=db.getConnection();var s=c.createStatement()){
            s.execute("CREATE FUNCTION test_fail_ledger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected ledger fault'; END $$");
            s.execute("CREATE TRIGGER test_ledger_fail BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION test_fail_ledger()");
        }
        try {assertThrows(PaymentException.class,()->service.transfer(command("10"),"alice","fault"));}
        finally{try(var c=db.getConnection();var s=c.createStatement()){s.execute("DROP TRIGGER test_ledger_fail ON ledger_entries");s.execute("DROP FUNCTION test_fail_ledger()");}}
        assertEquals("500.000",balance("A"));assertEquals("100.000",balance("B"));assertEquals(0,count("payments"));assertEquals(0,count("ledger_entries"));
    }
    @Test void deferredConstraintRejectsUnbalancedCompletedPayment()throws Exception {
        var cmd=command("10");
        try(var c=db.getConnection();var s=c.createStatement()){
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,payload_fingerprint,status,processed_at,requested_by,correlation_id) VALUES(?,'A','B',10,now(),'test',?,'COMPLETED',now(),'alice',?)")){
                p.setObject(1,cmd.paymentId());p.setString(2,cmd.fingerprint());p.setObject(3,UUID.randomUUID());p.executeUpdate();
            }
            assertThrows(SQLException.class,c::commit);c.rollback();
        }
        assertEquals(0,count("payments"));assertEquals("500.000",balance("A"));
    }
    @Test void lockContentionReturnsBoundedTransientErrorWithoutRejectedRecord()throws Exception {
        var cmd=command("1");
        try(var c=db.getConnection();var s=c.createStatement()){
            c.setAutoCommit(false);s.execute("SELECT * FROM accounts WHERE id='A' FOR UPDATE");
            long started=System.nanoTime();
            var e=assertThrows(PaymentException.class,()->service.transfer(cmd,"alice","lock"));
            assertEquals(503,e.http());assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toSeconds()<12);c.rollback();
        }
        assertEquals(0,count("payments"));assertEquals("500.000",balance("A"));
    }
    @Test void reconciliationDetectsPrivilegedLedgerCorruption()throws Exception {
        var result=service.transfer(command("10"),"alice","c");
        try(var c=db.getConnection();var s=c.createStatement()){
            s.execute("ALTER TABLE ledger_entries DISABLE TRIGGER immutable_ledger");
            try{s.execute("DELETE FROM ledger_entries WHERE entry_type='CREDIT'");}
            finally{s.execute("ALTER TABLE ledger_entries ENABLE TRIGGER immutable_ledger");}
        }
        var report=reconciliation.report();assertFalse(report.clean());assertEquals(List.of(result.payment().paymentId().toString()),report.invalidPayments());assertEquals(List.of("B"),report.balanceMismatches());
    }
    @Test void transfersRemainWritableAfterReconciliation()throws Exception {
        assertTrue(reconciliation.report().clean());
        assertEquals("COMPLETED",service.transfer(command("1"),"alice","c").payment().status());assertEquals("499.000",balance("A"));
    }
    <T>List<T> parallel(int n,Callable<T> task)throws Exception {
        try(var executor=Executors.newFixedThreadPool(n)){
            var latch=new CountDownLatch(1);List<Future<T>> futures=new ArrayList<>();for(int i=0;i<n;i++)futures.add(executor.submit(()->{latch.await();return task.call();}));latch.countDown();
            List<T> results=new ArrayList<>();for(var f:futures)results.add(f.get(30,TimeUnit.SECONDS));return results;
        }
    }
}
