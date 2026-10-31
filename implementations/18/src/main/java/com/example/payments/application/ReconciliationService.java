package com.example.payments.application;
import com.example.payments.domain.PaymentException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.time.Instant;

@ApplicationScoped
public class ReconciliationService {
    @Inject DataSource db;
    public record Report(Instant generatedAt,List<String> invalidPayments,List<String> balanceMismatches){ public boolean clean(){return invalidPayments.isEmpty()&&balanceMismatches.isEmpty();} }
    public Report report(){
        try(var c=db.getConnection()){
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                List<String> invalid=new ArrayList<>(),balances=new ArrayList<>();
                try(var s=c.createStatement()){
                    s.execute("SET LOCAL statement_timeout='10000ms'");
                    try(var r=s.executeQuery("SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON p.payment_id=l.payment_id GROUP BY p.payment_id HAVING (p.status='COMPLETED' AND (count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER(WHERE (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR (p.status<>'COMPLETED' AND count(l.id)<>0)")){
                        while(r.next())invalid.add(r.getString(1));
                    }
                    try(var r=s.executeQuery("SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON a.id=l.account_id GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")){
                        while(r.next())balances.add(r.getString(1));
                    }
                }
                c.commit();return new Report(Instant.now(),List.copyOf(invalid),List.copyOf(balances));
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }catch(SQLException e){throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE");}
    }
}
