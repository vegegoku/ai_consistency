package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.persistence.PaymentRepository;
import com.example.payments.observability.PaymentTelemetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class PaymentService {
    @Inject DataSource db; @Inject PaymentRepository repository; @Inject PaymentTelemetry telemetry;
    @ConfigProperty(name="payments.lock-timeout-ms") int lockTimeout;
    @ConfigProperty(name="payments.statement-timeout-ms") int statementTimeout;
    @ConfigProperty(name="payments.retries") int retries;

    public TransferResult transfer(PaymentCommand command,String principal,String correlation) {
        long started=System.nanoTime();String safeCorrelation;
        try{safeCorrelation=UUID.fromString(correlation).toString();}catch(Exception e){safeCorrelation=UUID.randomUUID().toString();}
        for(int attempt=0;;attempt++){
            try {
                TransferResult result=execute(command,principal,safeCorrelation);
                telemetry.committed(result,started);return result;
            } catch(SQLException e){
                String state=e.getSQLState();boolean transientLock=Set.of("40001","40P01","55P03","57014").contains(state==null?"":state);
                if(transientLock)telemetry.failure("LOCK_TIMEOUT");
                if(transientLock&&attempt<retries){
                    telemetry.failure("RETRY");
                    try{Thread.sleep(ThreadLocalRandom.current().nextInt(10,40)*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE",command.paymentId(),List.of());}
                    continue;
                }
                boolean unavailable=transientLock || state==null || state.startsWith("08") || state.startsWith("53") || state.startsWith("57") || state.startsWith("58");
                telemetry.failure(unavailable?"DATABASE_ERROR":"INTERNAL_ERROR");
                // Connection loss around commit is uncertain, and must be resolved using this same ID.
                var failure=new PaymentException(unavailable?503:500,unavailable?"TEMPORARILY_UNAVAILABLE":"INTERNAL_ERROR",command.paymentId(),List.of());failure.initCause(e);throw failure;
            }
        }
    }
    private TransferResult execute(PaymentCommand cmd,String principal,String correlation)throws SQLException {
        try(Connection c=db.getConnection()){
            c.setReadOnly(false);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                timeouts(c);
                boolean created=repository.register(c,cmd,principal,correlation);
                if(!created){
                    var existing=repository.find(c,cmd.paymentId());
                    if(existing==null)throw new SQLException("Concurrent outcome unavailable","40001");
                    if(!existing.requestedBy().equals(principal))throw new PaymentException(403,"FORBIDDEN",cmd.paymentId(),List.of());
                    if(!existing.fingerprint().equals(cmd.fingerprint()))throw new PaymentException(409,"IDEMPOTENCY_CONFLICT",cmd.paymentId(),List.of());
                    c.commit();return new TransferResult(existing,false);
                }
                var accounts=repository.lockAccounts(c,cmd.fromAccount(),cmd.toAccount());
                if(!repository.permitted(c,cmd.fromAccount(),principal,true))throw new PaymentException(403,"FORBIDDEN",cmd.paymentId(),List.of());
                var source=accounts.get(cmd.fromAccount());var destination=accounts.get(cmd.toAccount());
                String rejection=null;
                if(source==null||destination==null)rejection="FORBIDDEN";
                else if(!source.status().equals("ACTIVE")||!destination.status().equals("ACTIVE")||!source.customerStatus().equals("ACTIVE")||!destination.customerStatus().equals("ACTIVE"))rejection="ACCOUNT_NOT_ACTIVE";
                else if(!source.currency().equals("JOD")||!destination.currency().equals("JOD")||!destination.canReceive())rejection="FORBIDDEN";
                else if(cmd.amount().compareTo(source.maxPayment())>0||repository.dailyDebits(c,source.id()).add(cmd.amount()).compareTo(source.dailyLimit())>0)rejection="PAYMENT_LIMIT_EXCEEDED";
                else if(source.balance().compareTo(cmd.amount())<0)rejection="INSUFFICIENT_FUNDS";
                if(rejection==null){
                    BigDecimal debitBalance=source.balance().subtract(cmd.amount()),creditBalance=destination.balance().add(cmd.amount());
                    repository.balance(c,source.id(),debitBalance);
                    repository.balance(c,destination.id(),creditBalance);
                    repository.ledger(c,cmd,source.id(),"DEBIT",cmd.amount().negate(),debitBalance);
                    repository.ledger(c,cmd,destination.id(),"CREDIT",cmd.amount(),creditBalance);
                }
                repository.finish(c,cmd.paymentId(),rejection,correlation);
                StoredPayment payment=repository.find(c,cmd.paymentId());
                c.commit();return new TransferResult(payment,true);
            } catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
        }
    }
    private void timeouts(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("SET LOCAL TIME ZONE 'UTC'");
            s.execute("SET LOCAL lock_timeout='"+Math.max(1,lockTimeout)+"ms'");
            s.execute("SET LOCAL statement_timeout='"+Math.max(1,statementTimeout)+"ms'");
        }
    }
    public StoredPayment find(UUID id,String principal){
        try(var c=db.getConnection()){
            var payment=repository.find(c,id);
            if(payment==null||!payment.requestedBy().equals(principal))throw new PaymentException(404,"NOT_FOUND");
            return payment;
        }catch(SQLException e){throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE");}
    }
    public Map<String,String> balance(String id,String principal){
        try(var c=db.getConnection()){
            if(!repository.permitted(c,id,principal,false))throw new PaymentException(404,"NOT_FOUND");
            try(var s=c.prepareStatement("SELECT available_balance,currency FROM accounts WHERE id=?")){
                s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new PaymentException(404,"NOT_FOUND");return Map.of("accountId",id,"balance",r.getBigDecimal(1).setScale(3).toPlainString(),"currency",r.getString(2));}
            }
        }catch(SQLException e){throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE");}
    }
}
