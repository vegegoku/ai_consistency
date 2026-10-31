package com.example.payments.application;

/** Transaction lifecycle extension; throwing aborts the entire transaction. */
@FunctionalInterface
public interface TransferHook {
  void afterDebit();
}
