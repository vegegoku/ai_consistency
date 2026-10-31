package com.example.payments.application;

import com.example.payments.api.*;
import com.example.payments.domain.PaymentCommand;
import com.example.payments.persistence.PaymentStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class PaymentService {
  private final PaymentStore store;
  private final MessageCodec codec;
  private final FaultInjector fault;

  @Inject
  public PaymentService(PaymentStore store, MessageCodec codec) {
    this(store, codec, FaultInjector.NONE);
  }

  public PaymentService(PaymentStore store, MessageCodec codec, FaultInjector fault) {
    this.store = store;
    this.codec = codec;
    this.fault = fault;
  }

  public Outcome transfer(PaymentCommand p, String principal, String correlation) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try (var c = store.transaction()) {
        try {
          boolean fresh = store.register(c, p, principal, correlation);
          if (!fresh) {
            var existing = store.payment(c, p.paymentId());
            if (!existing.owner().equals(principal)) throw ApiFailure.business(403, "FORBIDDEN");
            if (!existing.fingerprint().equals(p.fingerprint()))
              throw ApiFailure.business(409, "IDEMPOTENCY_CONFLICT");
            c.commit();
            return existing.outcome();
          }
          codec.validateFreshness(p);
          var accounts = store.lockAccounts(c, p.fromAccount(), p.toAccount());
          var source = accounts.get(p.fromAccount());
          var target = accounts.get(p.toAccount());
          if (source == null || !store.authorized(c, p.fromAccount(), principal))
            throw ApiFailure.business(403, "FORBIDDEN");
          String rejection = null;
          if (target == null || !target.receive()) rejection = "FORBIDDEN";
          else if (!source.status().equals("ACTIVE")
              || !target.status().equals("ACTIVE")
              || !source.currency().equals("JOD")
              || !target.currency().equals("JOD")) rejection = "ACCOUNT_NOT_ACTIVE";
          else if (p.amount().compareTo(source.limit()) > 0) rejection = "PAYMENT_LIMIT_EXCEEDED";
          else if (source.balance().compareTo(p.amount()) < 0) rejection = "INSUFFICIENT_FUNDS";
          if (rejection == null) {
            var debit = source.balance().subtract(p.amount());
            var credit = target.balance().add(p.amount());
            store.balance(c, p.fromAccount(), debit);
            fault.afterDebit();
            store.balance(c, p.toAccount(), credit);
            store.ledger(c, p, p.fromAccount(), "DEBIT", p.amount().negate(), debit);
            store.ledger(c, p, p.toAccount(), "CREDIT", p.amount(), credit);
          }
          store.finish(c, p.paymentId(), rejection);
          var outcome = store.payment(c, p.paymentId()).outcome();
          c.commit();
          return new Outcome(rejection == null ? 201 : outcome.http(), outcome.body(), false);
        } catch (SQLException | RuntimeException e) {
          try {
            c.rollback();
          } catch (SQLException ignored) {
          }
          throw e;
        }
      } catch (SQLException e) {
        String state = e.getSQLState();
        org.jboss.logging.Logger.getLogger(PaymentService.class)
            .warnf("Database transfer failure SQLSTATE=%s", state);
        boolean retryable =
            Set.of("40001", "40P01", "55P03", "57014").contains(state == null ? "" : state);
        if (retryable && attempt < 2) {
          com.example.payments.observability.Telemetry.retry(state);
          try {
            Thread.sleep(20L * (attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
          }
          continue;
        }
        if (retryable
            || state == null
            || state.startsWith("08")
            || state.startsWith("53")
            || state.startsWith("57")
            || state.startsWith("58")) throw unavailable();
        throw new ApiFailure(500, "INTERNAL_ERROR", "The request could not be processed.");
      }
    }
    throw unavailable();
  }

  public Outcome find(UUID id, String principal) {
    try (var c = store.connection()) {
      var p = store.payment(c, id);
      if (p == null || !p.owner().equals(principal))
        throw new ApiFailure(404, "PAYMENT_NOT_FOUND", "Payment was not found.");
      return new Outcome(200, p.outcome().body(), true);
    } catch (SQLException e) {
      throw unavailable();
    }
  }

  public Map<String, Object> balance(String id, String principal) {
    try (var c = store.connection()) {
      if (!store.authorized(c, id, principal))
        throw new ApiFailure(404, "ACCOUNT_NOT_FOUND", "Account was not found.");
      try (var s = c.prepareStatement("SELECT available_balance FROM accounts WHERE id=?")) {
        s.setString(1, id);
        try (var r = s.executeQuery()) {
          if (!r.next()) throw new ApiFailure(404, "ACCOUNT_NOT_FOUND", "Account was not found.");
          return Map.of(
              "accountId", id, "balance", r.getBigDecimal(1).toPlainString(), "currency", "JOD");
        }
      }
    } catch (SQLException e) {
      throw unavailable();
    }
  }

  public List<Map<String, Object>> reconcile() {
    try {
      return store.reconciliation();
    } catch (SQLException e) {
      throw unavailable();
    }
  }

  private static ApiFailure unavailable() {
    return new ApiFailure(
        503,
        "TEMPORARILY_UNAVAILABLE",
        "Processing is temporarily unavailable. Query status or retry with the same paymentId.");
  }
}
