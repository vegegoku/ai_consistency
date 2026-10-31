package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.DatabaseFailures;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class PaymentService {
    public record Outcome(int http, Map<String,Object> body, boolean replay) {}
    private record Account(String id,String customer,BigDecimal balance,String state,String currency,boolean debit,boolean receive,BigDecimal max,BigDecimal daily) {}
    @Value("${payments.future-seconds:300}") private long futureSeconds=300;
    @Value("${payments.age-seconds:2592000}") private long ageSeconds=2592000;
    private final JdbcTemplate db; private final TransactionTemplate tx; private final Clock clock; private final MeterRegistry metrics; private final TransferFault fault; private final int attempts;
    public PaymentService(JdbcTemplate db,PlatformTransactionManager manager,Clock clock,MeterRegistry metrics,TransferFault fault,@Value("${payments.db-attempts:3}") int attempts){
        this.db=db;this.clock=clock;this.metrics=metrics;this.fault=fault;this.attempts=attempts;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Outcome transfer(PaymentCommand c,String principal,String correlation){
        long started=System.nanoTime();
        try {
            for(int attempt=0;attempt<attempts;attempt++) {
                try {
                    Outcome result=Objects.requireNonNull(tx.execute(status->execute(c,principal,correlation)));
                    // These side effects run after commit and cannot alter its financial outcome.
                    try {metrics.counter(result.replay()?"payments.replays":"payments.outcomes","code",String.valueOf(result.body().getOrDefault("code","COMPLETED"))).increment();}
                    catch(RuntimeException ignored) { /* Financial result takes precedence over telemetry availability. */ }
                    return result;
                } catch(RuntimeException e) {
                    if(!DatabaseFailures.retryable(e))throw e;
                    metrics.counter("payments.db.retries").increment();
                    if(e instanceof CannotAcquireLockException || DatabaseFailures.sqlState(e).equals("55P03"))metrics.counter("payments.db.lock.timeouts").increment();
                    if(attempt+1>=attempts)throw new ApiFailure(503,"TEMPORARILY_UNAVAILABLE");
                    try {Thread.sleep(20L*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ApiFailure(503,"TEMPORARILY_UNAVAILABLE");}
                }
            }
            throw new ApiFailure(503,"TEMPORARILY_UNAVAILABLE");
        } finally {try{metrics.timer("payments.processing").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);}catch(RuntimeException ignored){ /* Telemetry must not change committed outcomes. */ }}
    }
    private Outcome execute(PaymentCommand c,String principal,String correlation){
        db.execute("SET LOCAL lock_timeout='1500ms'");db.execute("SET LOCAL statement_timeout='5000ms'");
        int inserted=db.update("INSERT INTO payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id) VALUES (?,?,?,?,'JOD',?,?,?,?,'PENDING',?,?) ON CONFLICT(payment_id) DO NOTHING",c.paymentId(),c.fromAccount(),c.toAccount(),c.amount(),Timestamp.from(c.date()),c.purpose(),c.notes(),c.fingerprint(),principal,correlation);
        var payment=payment(c.paymentId());
        if(inserted==0){
            if(!Objects.equals(payment.get("requested_by"),principal))throw new ApiFailure(403,"FORBIDDEN");
            if(!Objects.equals(payment.get("payload_fingerprint"),c.fingerprint()))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT",c.paymentId(),List.of(),null,null);
            return outcome(payment,true);
        }
        Instant instructionNow=clock.instant();
        if(c.date().isAfter(instructionNow.plusSeconds(futureSeconds)) || c.date().isBefore(instructionNow.minusSeconds(ageSeconds)))throw new ApiFailure(400,"VALIDATION_FAILED",c.paymentId(),List.of(new ApiFailure.Issue("date","INVALID_DATE","An offset timestamp within the configured instruction window is required.")),null,null);
        var accounts=db.query("SELECT id,customer_id,available_balance,status,currency,debit_enabled,receive_enabled,max_single_payment,daily_limit FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",(r,n)->new Account(r.getString(1),r.getString(2),r.getBigDecimal(3),r.getString(4),r.getString(5),r.getBoolean(6),r.getBoolean(7),r.getBigDecimal(8),r.getBigDecimal(9)),c.fromAccount(),c.toAccount());
        var from=accounts.stream().filter(a->a.id().equals(c.fromAccount())).findFirst().orElse(null);
        var to=accounts.stream().filter(a->a.id().equals(c.toAccount())).findFirst().orElse(null);
        String rejection=null;int http=409;
        if(from==null || !canDebit(c.fromAccount(),principal)){rejection="FORBIDDEN";http=403;}
        else if(to==null){rejection="FORBIDDEN";http=403;}
        else if(!from.state().equals("ACTIVE")||!to.state().equals("ACTIVE")){rejection="ACCOUNT_NOT_ACTIVE";}
        else if(!from.currency().equals("JOD")||!to.currency().equals("JOD")||!from.debit()||!to.receive()){rejection="FORBIDDEN";http=403;}
        else {
            var used=db.queryForObject("SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND status='COMPLETED' AND processed_at>=?",BigDecimal.class,c.fromAccount(),Timestamp.from(clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()));
            if(c.amount().compareTo(from.max())>0 || used.add(c.amount()).compareTo(from.daily())>0)rejection="PAYMENT_LIMIT_EXCEEDED";
            else if(from.balance().compareTo(c.amount())<0)rejection="INSUFFICIENT_FUNDS";
        }
        Instant now=clock.instant();
        if(rejection!=null){
            db.update("UPDATE payments SET status='REJECTED',rejection_code=?,rejection_http=?,processed_at=? WHERE payment_id=?",rejection,http,Timestamp.from(now),c.paymentId());
            return outcome(payment(c.paymentId()),false);
        }
        BigDecimal source=from.balance().subtract(c.amount()),destination=to.balance().add(c.amount());
        db.update("UPDATE accounts SET available_balance=?,version=version+1,updated_at=? WHERE id=?",source,Timestamp.from(now),from.id());
        fault.afterDebit();
        db.update("UPDATE accounts SET available_balance=?,version=version+1,updated_at=? WHERE id=?",destination,Timestamp.from(now),to.id());
        db.update("INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after) VALUES (?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",c.paymentId(),from.id(),c.amount().negate(),source,c.paymentId(),to.id(),c.amount(),destination);
        db.update("UPDATE payments SET status='COMPLETED',processed_at=? WHERE payment_id=?",Timestamp.from(now),c.paymentId());
        return outcome(payment(c.paymentId()),false);
    }
    private boolean canDebit(String account,String principal){
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE a.id=? AND c.status='ACTIVE' AND (a.customer_id=? OR EXISTS(SELECT 1 FROM account_authorizations x WHERE x.account_id=a.id AND x.principal=? AND x.can_debit)))",Boolean.class,account,principal,principal));
    }
    private boolean canView(String account,String principal){
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts a WHERE a.id=? AND (a.customer_id=? OR EXISTS(SELECT 1 FROM account_authorizations x WHERE x.account_id=a.id AND x.principal=? AND (x.can_view OR x.can_debit))))",Boolean.class,account,principal,principal));
    }
    private Map<String,Object> payment(UUID id){var rows=db.queryForList("SELECT * FROM payments WHERE payment_id=?",id);if(rows.isEmpty())throw new ApiFailure(404,"NOT_FOUND");return rows.getFirst();}
    public Outcome find(UUID id,String principal){
        var p=payment(id);
        if(!Objects.equals(p.get("requested_by"),principal)&&!canView((String)p.get("from_account_id"),principal)&&!canView((String)p.get("to_account_id"),principal))throw new ApiFailure(404,"NOT_FOUND");
        return outcome(p,true);
    }
    public Map<String,Object> balance(String account,String principal){if(!canView(account,principal))throw new ApiFailure(404,"NOT_FOUND");return Map.of("accountId",account,"balance",db.queryForObject("SELECT available_balance FROM accounts WHERE id=?",BigDecimal.class,account).toPlainString(),"currency","JOD");}
    private Outcome outcome(Map<String,Object> p,boolean replay){
        var body=new LinkedHashMap<String,Object>();body.put("paymentId",p.get("payment_id").toString());body.put("status",p.get("status"));
        if(p.get("status").equals("REJECTED")){
            String code=(String)p.get("rejection_code");body.put("code",code);body.put("message","The payment could not be completed.");
            body.put("issues",code.equals("INSUFFICIENT_FUNDS")?List.of(Map.of("field","amount","code",code,"message","Available funds are insufficient.")):List.of());
            body.put("correlationId",p.get("correlation_id"));body.put("timestamp",((Timestamp)p.get("processed_at")).toInstant().toString());
            return new Outcome(((Number)p.get("rejection_http")).intValue(),Collections.unmodifiableMap(body),replay);
        }
        body.put("fromAccount",p.get("from_account_id"));body.put("toAccount",p.get("to_account_id"));body.put("amount",((BigDecimal)p.get("amount")).toPlainString());body.put("currency","JOD");body.put("processedAt",((Timestamp)p.get("processed_at")).toInstant().toString());
        return new Outcome(replay?200:201,Collections.unmodifiableMap(body),replay);
    }
    public List<Map<String,Object>> reconcile(){
        TransactionTemplate snapshot=new TransactionTemplate(tx.getTransactionManager());snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setReadOnly(true);snapshot.setTimeout(30);
        return snapshot.execute(s->{
            var issues=new ArrayList<Map<String,Object>>();
            issues.addAll(db.queryForList("SELECT 'PAYMENT_LEDGER' AS kind,p.payment_id::text AS reference FROM payments p LEFT JOIN ledger_entries l ON l.payment_id=p.payment_id GROUP BY p.payment_id HAVING (p.status='COMPLETED' AND (count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR (p.status<>'COMPLETED' AND count(l.id)<>0)"));
            issues.addAll(db.queryForList("SELECT 'ACCOUNT_BALANCE' AS kind,a.id AS reference FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)"));
            issues.addAll(db.queryForList("SELECT 'LEDGER_SNAPSHOT' AS kind,id::text AS reference FROM (SELECT l.id,l.balance_after,a.opening_balance+sum(l.signed_amount) OVER(PARTITION BY l.account_id ORDER BY l.id) AS expected FROM ledger_entries l JOIN accounts a ON a.id=l.account_id) s WHERE balance_after<>expected"));
            return issues;
        });
    }
}
