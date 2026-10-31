package com.example.payments.application;

import com.example.payments.domain.ApiProblem;
import com.example.payments.persistence.PaymentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class ReconciliationService {
    @Inject PaymentRepository repo;
    public Map<String,Object> report() {
        try(Connection c=repo.connect()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
            try(Statement s=c.createStatement()) {s.execute("SET LOCAL statement_timeout='10000ms'");}
            List<Map<String,Object>> failures=new ArrayList<>();
            String ledger="SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l ON p.payment_id=l.payment_id GROUP BY p.payment_id HAVING (p.status='COMPLETED' AND (count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0 OR count(l.id) FILTER (WHERE (l.entry_type='DEBIT' AND l.account_id=p.from_account_id AND l.signed_amount=-p.amount) OR (l.entry_type='CREDIT' AND l.account_id=p.to_account_id AND l.signed_amount=p.amount))<>2)) OR (p.status<>'COMPLETED' AND count(l.id)<>0) OR p.status='PROCESSING'";
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(ledger)) {while(r.next())failures.add(Map.of("type","PAYMENT_LEDGER_MISMATCH","paymentId",r.getString(1)));}
            String balances="SELECT a.id,a.available_balance,a.opening_balance+coalesce(sum(l.signed_amount),0) AS expected FROM accounts a LEFT JOIN ledger_entries l ON a.id=l.account_id GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)";
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(balances)) {while(r.next())failures.add(Map.of("type","ACCOUNT_BALANCE_MISMATCH","accountId",r.getString(1),"persisted",r.getBigDecimal(2).toPlainString(),"ledgerDerived",r.getBigDecimal(3).toPlainString()));}
            String checkpoints="SELECT account_id,id,balance_after,expected FROM (SELECT l.account_id,l.id,l.balance_after,a.opening_balance+sum(l.signed_amount) OVER (PARTITION BY l.account_id ORDER BY l.id ROWS UNBOUNDED PRECEDING) AS expected FROM ledger_entries l JOIN accounts a ON a.id=l.account_id) q WHERE balance_after<>expected";
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery(checkpoints)) {while(r.next())failures.add(Map.of("type","LEDGER_CHECKPOINT_MISMATCH","accountId",r.getString(1),"ledgerEntryId",r.getLong(2)));}
            c.commit();return Map.of("timestamp",Instant.now().toString(),"healthy",failures.isEmpty(),"issues",failures);
        }catch(SQLException failure){throw new ApiProblem(503,"TEMPORARILY_UNAVAILABLE");}
    }
}
