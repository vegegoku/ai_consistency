package com.example.payments.application;

/** Internal extension point used by fault-injection tests; never exposed over HTTP. */
public interface TransferHook {
  void afterDebit();
}
