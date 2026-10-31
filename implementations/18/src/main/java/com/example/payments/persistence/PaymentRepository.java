package com.example.payments.persistence;

import com.example.payments.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class PaymentRepository {
    public record Account(String id,String customerId,String status,String currency,BigDecimal balance,boolean canReceive,BigDecimal maxPayment,BigDecimal dailyLimit,String customerStatus){}
    public StoredPayment find(Connection c,UUID id)throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")){s.setObject(1,id);try(var r=s.executeQuery()){
            if(!r.next())return null;
            return new StoredPayment(id,r.getString("status"),r.getString("from_account_id"),r.getString("to_account_id"),r.getBigDecimal("amount").setScale(3).toPlainString(),r.getString("currency"),r.getTimestamp("processed_at").toInstant(),r.getString("rejection_code"),r.getString("requested_by"),r.getString("payload_fingerprint"),r.getString("correlation_id"));
        }}
    }
    public boolean register(Connection c,PaymentCommand cmd,String principal,String correlation)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id) VALUES(?,?,?,?,?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO NOTHING")){
            s.setObject(1,cmd.paymentId());s.setString(2,cmd.fromAccount());s.setString(3,cmd.toAccount());s.setBigDecimal(4,cmd.amount());s.setTimestamp(5,Timestamp.from(cmd.date()));s.setString(6,cmd.purpose());s.setString(7,cmd.notes());s.setString(8,cmd.fingerprint());s.setString(9,principal);s.setObject(10,UUID.fromString(correlation));return s.executeUpdate()==1;
        }
    }
    public Map<String,Account> lockAccounts(Connection c,String from,String to)throws SQLException {
        Map<String,Account> accounts=new HashMap<>();
        try(var s=c.prepareStatement("SELECT a.*,c.status AS customer_status FROM accounts a JOIN customers c ON c.id=a.customer_id WHERE a.id IN (?,?) ORDER BY a.id FOR UPDATE OF a")){
            s.setString(1,from);s.setString(2,to);try(var r=s.executeQuery()){while(r.next()){
                var a=new Account(r.getString("id"),r.getString("customer_id"),r.getString("status"),r.getString("currency"),r.getBigDecimal("available_balance"),r.getBoolean("can_receive"),r.getBigDecimal("max_payment"),r.getBigDecimal("daily_debit_limit"),r.getString("customer_status"));accounts.put(a.id(),a);
            }}
        }return accounts;
    }
    public boolean permitted(Connection c,String account,String principal,boolean debit)throws SQLException {
        String permission=debit?"can_debit":"can_view";
        try(var s=c.prepareStatement("SELECT 1 FROM accounts a WHERE a.id=? AND (a.customer_id=? OR EXISTS(SELECT 1 FROM account_grants g WHERE g.account_id=a.id AND g.principal=? AND g."+permission+"))")){
            s.setString(1,account);s.setString(2,principal);s.setString(3,principal);try(var r=s.executeQuery()){return r.next();}
        }
    }
    public BigDecimal dailyDebits(Connection c,String account)throws SQLException {
        try(var s=c.prepareStatement("SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND status='COMPLETED' AND processed_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'")){
            s.setString(1,account);try(var r=s.executeQuery()){r.next();return r.getBigDecimal(1);}
        }
    }
    public void balance(Connection c,String id,BigDecimal balance)throws SQLException {
        try(var s=c.prepareStatement("UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?")){s.setBigDecimal(1,balance);s.setString(2,id);if(s.executeUpdate()!=1)throw new SQLException("Missing locked account");}
    }
    public void ledger(Connection c,PaymentCommand cmd,String account,String type,BigDecimal signed,BigDecimal balance)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after) VALUES(?,?,?,?,?)")){
            s.setObject(1,cmd.paymentId());s.setString(2,account);s.setString(3,type);s.setBigDecimal(4,signed);s.setBigDecimal(5,balance);s.executeUpdate();
        }
    }
    public void finish(Connection c,UUID id,String rejection,String correlation)throws SQLException {
        try(var s=c.prepareStatement("UPDATE payments SET status=?,rejection_code=?,processed_at=clock_timestamp() WHERE payment_id=?")){
            s.setString(1,rejection==null?"COMPLETED":"REJECTED");s.setString(2,rejection);s.setObject(3,id);s.executeUpdate();
        }
        if(rejection!=null)try(var s=c.prepareStatement("INSERT INTO payment_issues(payment_id,code,safe_message,correlation_id) VALUES(?,?,'The payment could not be completed.',?)")){
            s.setObject(1,id);s.setString(2,rejection);s.setObject(3,UUID.fromString(correlation));s.executeUpdate();
        }
    }
}
