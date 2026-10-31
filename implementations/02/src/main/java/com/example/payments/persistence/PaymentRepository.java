package com.example.payments.persistence;

import com.example.payments.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;

@ApplicationScoped
public class PaymentRepository {
    @Inject DataSource dataSource;
    public Connection connect() throws SQLException { return dataSource.getConnection(); }
    public record Account(String id,String customer,String state,String customerState,String currency,BigDecimal balance,
                          String receivePolicy,BigDecimal maxTransfer,BigDecimal dailyLimit,boolean debit,boolean receive,boolean read) {}
    public Account account(Connection c,String id,String principal,boolean lock) throws SQLException {
        String sql="SELECT a.*,cu.status AS customer_state,coalesce(p.can_debit,false) AS debit,coalesce(p.can_receive,false) AS receive,coalesce(p.can_read,false) AS readable FROM accounts a JOIN customers cu ON cu.id=a.customer_id LEFT JOIN account_permissions p ON p.account_id=a.id AND p.principal=? WHERE a.id=?"+(lock?" FOR UPDATE OF a":"");
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,principal);s.setString(2,id);
            try(ResultSet r=s.executeQuery()) { if(!r.next())return null;
                boolean owner=principal.equals(r.getString("customer_id"));
                return new Account(id,r.getString("customer_id"),r.getString("status"),r.getString("customer_state"),r.getString("currency"),r.getBigDecimal("available_balance"),r.getString("receive_policy"),r.getBigDecimal("max_transfer_amount"),r.getBigDecimal("daily_outgoing_limit"),owner||r.getBoolean("debit"),owner||r.getBoolean("receive"),owner||r.getBoolean("readable"));
            }
        }
    }
    public boolean register(Connection c,PaymentCommand p,String principal,String correlation) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO payments(payment_id,from_account_id,to_account_id,amount,currency,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id) VALUES (?,?,?,?,'JOD',?,?,?,?,'PROCESSING',?,?) ON CONFLICT(payment_id) DO NOTHING")) {
            s.setObject(1,UUID.fromString(p.paymentId()));s.setString(2,p.fromAccount());s.setString(3,p.toAccount());s.setBigDecimal(4,p.amount());s.setTimestamp(5,Timestamp.from(p.date()));s.setString(6,p.purpose());s.setString(7,p.notes());s.setString(8,p.fingerprint());s.setString(9,principal);s.setObject(10,UUID.fromString(correlation));return s.executeUpdate()==1;
        }
    }
    public PaymentRecord find(Connection c,String id) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
            s.setObject(1,UUID.fromString(id));
            try(ResultSet r=s.executeQuery()) { if(!r.next())return null;
                return new PaymentRecord(r.getString("payment_id"),r.getString("status"),r.getString("from_account_id"),r.getString("to_account_id"),r.getBigDecimal("amount"),r.getTimestamp("processed_at")==null?null:r.getTimestamp("processed_at").toInstant(),r.getString("requested_by"),r.getString("payload_fingerprint"),r.getString("rejection_code"),r.getInt("rejection_http"),r.getString("correlation_id"));
            }
        }
    }
    public void finish(Connection c,String id,String code,int http) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE payments SET status=?,rejection_code=?,rejection_http=?,processed_at=clock_timestamp() WHERE payment_id=?")) {
            s.setString(1,code==null?"COMPLETED":"REJECTED");s.setString(2,code);if(code==null)s.setNull(3,Types.INTEGER);else s.setInt(3,http);s.setObject(4,UUID.fromString(id));s.executeUpdate();
        }
    }
    public void issue(Connection c,String id,String code,String correlation) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO payment_issues(payment_id,code,safe_message,correlation_id) VALUES (?,?,?,?)")) {
            s.setObject(1,UUID.fromString(id));s.setString(2,code);s.setString(3,"The payment could not be completed.");s.setObject(4,UUID.fromString(correlation));s.executeUpdate();
        }
    }
    public void balance(Connection c,String account,BigDecimal amount) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE accounts SET available_balance=?,version=version+1,updated_at=clock_timestamp() WHERE id=?")) {s.setBigDecimal(1,amount);s.setString(2,account);if(s.executeUpdate()!=1)throw new SQLException("Account update failed");}
    }
    public void ledger(Connection c,String payment,String account,String type,BigDecimal amount,BigDecimal after) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after) VALUES (?,?,?,?,?)")) {s.setObject(1,UUID.fromString(payment));s.setString(2,account);s.setString(3,type);s.setBigDecimal(4,amount);s.setBigDecimal(5,after);s.executeUpdate();}
    }
    public BigDecimal outgoingToday(Connection c,String account) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT coalesce(sum(amount),0) FROM payments WHERE from_account_id=? AND status='COMPLETED' AND processed_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'")) {s.setString(1,account);try(ResultSet r=s.executeQuery()){r.next();return r.getBigDecimal(1);}}
    }
}
