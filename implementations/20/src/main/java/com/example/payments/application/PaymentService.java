package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class PaymentService {
  @Inject AgroalDataSource db;
  @Inject ObjectMapper mapper;
  @Inject TransferFault fault;
  @Inject MeterRegistry metrics;

  public record Outcome(int http, Map<String, Object> body, boolean replay) {}

  private record Account(
      String id,
      String customer,
      String currency,
      BigDecimal balance,
      String status,
      boolean receive,
      BigDecimal limit) {}

  public Outcome transfer(PaymentCommand p, Caller caller, UUID correlation) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try (Connection c = db.getConnection()) {
        c.setAutoCommit(false);
        try {
          try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL lock_timeout='2s'");
            st.execute("SET LOCAL statement_timeout='5s'");
          }
          String fingerprint = fingerprint(p);
          int inserted;
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO payments(payment_id,from_account_id,to_account_id,amount,instruction_date,purpose,notes,payload_fingerprint,status,requested_by,correlation_id) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?) ON CONFLICT(payment_id) DO NOTHING")) {
            bind(
                s,
                p.paymentId(),
                p.fromAccount(),
                p.toAccount(),
                p.amount(),
                Timestamp.from(p.date()),
                p.purpose(),
                p.notes(),
                fingerprint,
                caller.subject(),
                correlation);
            inserted = s.executeUpdate();
          }
          if (inserted == 0) {
            Map<String, Object> old = payment(c, p.paymentId());
            if (!caller.admin() && !caller.subject().equals(old.get("requested_by")))
              throw new ApiFailure(403, "FORBIDDEN");
            if (!fingerprint.equals(old.get("payload_fingerprint")))
              throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT");
            Outcome result = outcome(old, true);
            c.commit();
            safeMetric("payments.replays", "outcome", "recorded");
            return result;
          }
          Map<String, Account> accounts = new HashMap<>();
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
            bind(s, p.fromAccount(), p.toAccount());
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) accounts.put(r.getString("id"), account(r));
            }
          }
          Account from = accounts.get(p.fromAccount()), to = accounts.get(p.toAccount());
          String code = null;
          int http = 409;
          if (from == null || !authorized(c, from, caller, true)) {
            code = caller.admin() && from == null ? "ACCOUNT_NOT_FOUND" : "FORBIDDEN";
            http = caller.admin() && from == null ? 404 : 403;
          } else if (to == null) {
            code = caller.admin() ? "ACCOUNT_NOT_FOUND" : "FORBIDDEN";
            http = caller.admin() ? 404 : 403;
          } else if (!to.receive()) {
            code = "FORBIDDEN";
            http = 403;
          } else if (!from.status().equals("ACTIVE") || !to.status().equals("ACTIVE")) {
            code = "ACCOUNT_NOT_ACTIVE";
          } else if (!from.currency().equals("JOD") || !to.currency().equals("JOD")) {
            code = "FORBIDDEN";
            http = 403;
          } else if (p.amount().compareTo(from.limit()) > 0) {
            code = "PAYMENT_LIMIT_EXCEEDED";
          } else if (from.balance().compareTo(p.amount()) < 0) {
            code = "INSUFFICIENT_FUNDS";
          }
          if (code != null) {
            try (PreparedStatement s =
                c.prepareStatement(
                    "UPDATE payments SET status='REJECTED',rejection_code=?,rejection_http=?,processed_at=now() WHERE payment_id=?")) {
              bind(s, code, http, p.paymentId());
              s.executeUpdate();
            }
          } else {
            BigDecimal debit = from.balance().subtract(p.amount()),
                credit = to.balance().add(p.amount());
            updateBalance(c, from.id(), debit);
            fault.afterDebit();
            updateBalance(c, to.id(), credit);
            ledger(c, p.paymentId(), from.id(), "DEBIT", p.amount().negate(), debit);
            ledger(c, p.paymentId(), to.id(), "CREDIT", p.amount(), credit);
            try (PreparedStatement s =
                c.prepareStatement(
                    "UPDATE payments SET status='COMPLETED',processed_at=now() WHERE payment_id=?")) {
              bind(s, p.paymentId());
              s.executeUpdate();
            }
          }
          Outcome result = outcome(payment(c, p.paymentId()), false);
          c.commit();
          safeMetric("payments.results", "code", code == null ? "COMPLETED" : code);
          return result;
        } catch (Exception e) {
          try {
            c.rollback();
          } catch (SQLException ignored) {
          }
          throw e;
        }
      } catch (ApiFailure e) {
        throw e;
      } catch (SQLException e) {
        String state = e.getSQLState();
        if (("40001".equals(state) || "40P01".equals(state)) && attempt < 2) {
          safeMetric("payments.retries", "reason", "serialization");
          continue;
        }
        safeMetric(
            "payments.database.failures",
            "reason",
            ("55P03".equals(state) || "57014".equals(state)) ? "timeout" : "unavailable");
        throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
      } catch (Exception e) {
        safeMetric("payments.failures", "reason", "unexpected");
        throw new ApiFailure(500, "INTERNAL_ERROR");
      }
    }
    throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
  }

  public Outcome get(UUID id, Caller caller) {
    try (Connection c = db.getConnection()) {
      Map<String, Object> p = payment(c, id);
      if (p == null || (!caller.admin() && !caller.subject().equals(p.get("requested_by"))))
        throw new ApiFailure(404, "PAYMENT_NOT_FOUND");
      Outcome stored = outcome(p, true);
      return new Outcome(200, stored.body(), true);
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public Map<String, Object> balance(String id, Caller caller) {
    try (Connection c = db.getConnection();
        PreparedStatement s = c.prepareStatement("SELECT * FROM accounts WHERE id=?")) {
      bind(s, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new ApiFailure(404, "ACCOUNT_NOT_FOUND");
        Account a = account(r);
        if (!authorized(c, a, caller, false)) throw new ApiFailure(404, "ACCOUNT_NOT_FOUND");
        return Map.of(
            "accountId",
            id,
            "availableBalance",
            a.balance().setScale(3).toPlainString(),
            "currency",
            "JOD");
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  public List<Map<String, Object>> reconcile() {
    try (Connection c = db.getConnection();
        Statement s = c.createStatement()) {
      List<Map<String, Object>> issues = new ArrayList<>();
      try (ResultSet r =
          s.executeQuery(
              "SELECT p.payment_id FROM payments p LEFT JOIN ledger_entries l USING(payment_id) WHERE p.status='COMPLETED' GROUP BY p.payment_id HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0")) {
        while (r.next())
          issues.add(Map.of("type", "PAYMENT_LEDGER_MISMATCH", "paymentId", r.getString(1)));
      }
      try (ResultSet r =
          s.executeQuery(
              "SELECT a.id FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0)")) {
        while (r.next())
          issues.add(Map.of("type", "ACCOUNT_BALANCE_MISMATCH", "accountId", r.getString(1)));
      }
      return issues;
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }

  private boolean authorized(Connection c, Account a, Caller caller, boolean debit)
      throws SQLException {
    if (caller.admin() || a.customer().equals(caller.customer())) return true;
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT can_debit,can_view FROM account_permissions WHERE principal=? AND account_id=?")) {
      bind(s, caller.subject(), a.id());
      try (ResultSet r = s.executeQuery()) {
        return r.next() && r.getBoolean(debit ? "can_debit" : "can_view");
      }
    }
  }

  private Account account(ResultSet r) throws SQLException {
    return new Account(
        r.getString("id"),
        r.getString("customer_id"),
        r.getString("currency"),
        r.getBigDecimal("available_balance"),
        r.getString("status"),
        r.getBoolean("receive_enabled"),
        r.getBigDecimal("transfer_limit"));
  }

  private Map<String, Object> payment(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT * FROM payments WHERE payment_id=?")) {
      bind(s, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return null;
        Map<String, Object> m = new HashMap<>();
        for (String f :
            List.of(
                "payment_id",
                "status",
                "from_account_id",
                "to_account_id",
                "currency",
                "rejection_code",
                "requested_by",
                "payload_fingerprint",
                "correlation_id")) m.put(f, r.getString(f));
        m.put("amount", r.getBigDecimal("amount").setScale(3).toPlainString());
        m.put(
            "processedAt",
            r.getTimestamp("processed_at") == null
                ? null
                : r.getTimestamp("processed_at").toInstant().toString());
        m.put("rejection_http", r.getInt("rejection_http"));
        return m;
      }
    }
  }

  private Outcome outcome(Map<String, Object> p, boolean replay) {
    if (p == null) throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("paymentId", p.get("payment_id"));
    b.put("status", p.get("status"));
    if (p.get("status").equals("REJECTED")) {
      b.put("code", p.get("rejection_code"));
      b.put("message", "The payment could not be completed.");
      b.put("issues", List.of());
      b.put("correlationId", p.get("correlation_id"));
      b.put("timestamp", p.get("processedAt"));
      return new Outcome((Integer) p.get("rejection_http"), b, replay);
    }
    if (!p.get("status").equals("COMPLETED")) throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    b.put("fromAccount", p.get("from_account_id"));
    b.put("toAccount", p.get("to_account_id"));
    b.put("amount", p.get("amount"));
    b.put("currency", p.get("currency"));
    b.put("processedAt", p.get("processedAt"));
    return new Outcome(replay ? 200 : 201, b, replay);
  }

  private void updateBalance(Connection c, String id, BigDecimal b) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "UPDATE accounts SET available_balance=?,version=version+1,updated_at=now() WHERE id=?")) {
      bind(s, b, id);
      s.executeUpdate();
    }
  }

  private void ledger(
      Connection c, UUID id, String account, String type, BigDecimal value, BigDecimal balance)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO ledger_entries(payment_id,account_id,entry_type,signed_amount,balance_after) VALUES(?,?,?,?,?)")) {
      bind(s, id, account, type, value, balance);
      s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
  }

  private String fingerprint(PaymentCommand p) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      mapper.writeValueAsBytes(
                          Arrays.asList(
                              p.paymentId().toString(),
                              p.fromAccount(),
                              p.toAccount(),
                              p.amount().toPlainString(),
                              p.date().toString(),
                              p.purpose(),
                              p.notes()))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void safeMetric(String name, String tag, String value) {
    try {
      metrics.counter(name, tag, value).increment();
    } catch (RuntimeException ignored) {
    }
  }
}
