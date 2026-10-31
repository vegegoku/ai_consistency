package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.PaymentRepository;
import com.example.payments.persistence.PaymentRepository.Account;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class PaymentService {
    @Inject PaymentRepository repo;
    @Inject TransferProbe probe;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name="payments.lock-timeout-ms",defaultValue="1000") int lockTimeout;
    @ConfigProperty(name="payments.statement-timeout-ms",defaultValue="5000") int statementTimeout;
    @ConfigProperty(name="payments.transient-retries",defaultValue="2") int retries;
    @ConfigProperty(name="payments.max-amount",defaultValue="10000.000") java.math.BigDecimal maxAmount;
    @ConfigProperty(name="payments.max-age-days",defaultValue="30") long maxAgeDays;
    @ConfigProperty(name="payments.future-seconds",defaultValue="300") long futureSeconds;
    public record Outcome(PaymentRecord payment,boolean replay) {}

    public Outcome transfer(PaymentCommand command,String principal,String correlation) {
        long start=System.nanoTime();
        try {
            for(int attempt=0;;attempt++) {
                try {
                    Outcome result=execute(command,principal,correlation);
                    // Database commit has completed. Telemetry can never undo it.
                    safeMetric(result.replay()?"payments.replays":result.payment().status().equals("COMPLETED")?"payments.completed":"payments.rejected",result.payment().rejectionCode());
                    return result;
                } catch(SQLException failure) {
                    String state=failure.getSQLState();
                    if("55P03".equals(state)||"57014".equals(state))safeMetric("payments.lock.timeouts",null);
                    boolean retryable=Set.of("40001","40P01","55P03").contains(state==null?"":state);
                    boolean unavailable=state==null||state.startsWith("08")||state.startsWith("53")||state.startsWith("57")||retryable||failure instanceof SQLTransientException||failure instanceof SQLRecoverableException;
                    if(!unavailable)throw new ApiProblem(500,"INTERNAL_ERROR",command.paymentId(),List.of());
                    if(!retryable||attempt>=retries)throw new ApiProblem(503,"TEMPORARILY_UNAVAILABLE",command.paymentId(),List.of());
                    safeMetric("payments.retries",null);
                    try { Thread.sleep(25L*(attempt+1)); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiProblem(503,"TEMPORARILY_UNAVAILABLE");}
                }
            }
        } finally {try {metrics.timer("payments.processing.latency").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);}catch(RuntimeException ignored){}}
    }
    Outcome execute(PaymentCommand p,String principal,String correlation) throws SQLException {
        try(Connection c=repo.connect()) {
            c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try(Statement s=c.createStatement()) {s.execute("SET LOCAL lock_timeout='"+Math.max(1,lockTimeout)+"ms'");s.execute("SET LOCAL statement_timeout='"+Math.max(1,statementTimeout)+"ms'");}
                boolean fresh=repo.register(c,p,principal,correlation);
                if(!fresh) {
                    PaymentRecord original=repo.find(c,p.paymentId());
                    if(original==null)throw new SQLException("Concurrent payment unresolved","40001");
                    if(!original.requestedBy().equals(principal))throw new ApiProblem(403,"FORBIDDEN",p.paymentId(),List.of());
                    if(!original.fingerprint().equals(p.fingerprint()))throw new ApiProblem(409,"IDEMPOTENCY_CONFLICT",p.paymentId(),List.of());
                    c.commit();return new Outcome(original,true);
                }
                var issues=new ArrayList<ApiProblem.Issue>();
                if(p.amount().compareTo(maxAmount)>0)issues.add(new ApiProblem.Issue("amount","VALIDATION_FAILED","Amount exceeds the configured maximum."));
                var now=java.time.Instant.now();
                if(p.date().isAfter(now.plusSeconds(futureSeconds))||p.date().isBefore(now.minus(java.time.Duration.ofDays(maxAgeDays))))issues.add(new ApiProblem.Issue("date","VALIDATION_FAILED","Instruction is outside the configured time window."));
                if(!issues.isEmpty())throw new ApiProblem(400,"VALIDATION_FAILED",p.paymentId(),issues);
                Map<String,Account> accounts=new HashMap<>();
                for(String id:new TreeSet<>(List.of(p.fromAccount(),p.toAccount()))) accounts.put(id,repo.account(c,id,principal,true));
                Account source=accounts.get(p.fromAccount()),dest=accounts.get(p.toAccount());
                String code=null;int http=409;
                if(source==null||!source.debit()||dest==null||!(dest.receive()||dest.receivePolicy().equals("PUBLIC"))) {code="FORBIDDEN";http=403;}
                else if(!source.state().equals("ACTIVE")||!dest.state().equals("ACTIVE")||!source.customerState().equals("ACTIVE")||!dest.customerState().equals("ACTIVE"))code="ACCOUNT_NOT_ACTIVE";
                else if(!source.currency().equals("JOD")||!dest.currency().equals("JOD")) {code="FORBIDDEN";http=403;}
                else if(dest.balance().add(p.amount()).compareTo(new java.math.BigDecimal("9999999999999999.999"))>0)code="PAYMENT_LIMIT_EXCEEDED";
                else if(source.maxTransfer()!=null&&p.amount().compareTo(source.maxTransfer())>0||source.dailyLimit()!=null&&repo.outgoingToday(c,source.id()).add(p.amount()).compareTo(source.dailyLimit())>0)code="PAYMENT_LIMIT_EXCEEDED";
                else if(source.balance().compareTo(p.amount())<0)code="INSUFFICIENT_FUNDS";
                if(code!=null) {repo.finish(c,p.paymentId(),code,http);repo.issue(c,p.paymentId(),code,correlation);}
                else {
                    var debit=source.balance().subtract(p.amount());var credit=dest.balance().add(p.amount());
                    repo.balance(c,source.id(),debit);probe.afterDebit(c,p);
                    repo.balance(c,dest.id(),credit);
                    repo.ledger(c,p.paymentId(),source.id(),"DEBIT",p.amount().negate(),debit);
                    repo.ledger(c,p.paymentId(),dest.id(),"CREDIT",p.amount(),credit);
                    repo.finish(c,p.paymentId(),null,0);
                }
                PaymentRecord record=repo.find(c,p.paymentId());c.commit();return new Outcome(record,false);
            } catch(SQLException|RuntimeException failure) {try {c.rollback();}catch(SQLException ignored){} throw failure;}
        }
    }
    public PaymentRecord get(String id,String principal) {
        try(Connection c=repo.connect()) {
            PaymentRecord p=repo.find(c,id);
            if(p==null)throw new ApiProblem(404,"NOT_FOUND");
            boolean visible=p.requestedBy().equals(principal);
            if(!visible) {var a=repo.account(c,p.fromAccount(),principal,false);var b=repo.account(c,p.toAccount(),principal,false);visible=a!=null&&a.read()||b!=null&&b.read();}
            if(!visible)throw new ApiProblem(404,"NOT_FOUND");return p;
        } catch(IllegalArgumentException invalid){throw new ApiProblem(404,"NOT_FOUND");}
        catch(SQLException unavailable){throw new ApiProblem(503,"TEMPORARILY_UNAVAILABLE");}
    }
    public Map<String,String> balance(String id,String principal) {
        try(Connection c=repo.connect()) {var a=repo.account(c,id,principal,false);if(a==null||!a.read())throw new ApiProblem(404,"NOT_FOUND");return Map.of("accountId",id,"availableBalance",a.balance().setScale(3).toPlainString(),"currency",a.currency());}
        catch(SQLException unavailable){throw new ApiProblem(503,"TEMPORARILY_UNAVAILABLE");}
    }
    private void safeMetric(String name,String code) {try {metrics.counter(name,"code",code==null?"NONE":code).increment();}catch(RuntimeException ignored){}}
}
