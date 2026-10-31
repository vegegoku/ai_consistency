package com.example.payments.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

/** Guards staged financial writes against execution outside an active transaction. */
@ApplicationScoped
public class TransferProbe {
  @Inject TransactionSynchronizationRegistry transactions;

  public void afterDebit() {
    if (transactions.getTransactionStatus() != Status.STATUS_ACTIVE)
      throw new IllegalStateException("Financial writes require an active transaction");
  }
}
