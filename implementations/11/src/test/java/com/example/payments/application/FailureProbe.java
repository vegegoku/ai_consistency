package com.example.payments.application;

import java.util.concurrent.atomic.AtomicBoolean;

public class FailureProbe implements TransferHook {
  private final AtomicBoolean fail = new AtomicBoolean();

  public void failOnce() {
    fail.set(true);
  }

  public void clear() {
    fail.set(false);
  }

  public void afterDebit() {
    if (fail.getAndSet(false)) throw new IllegalStateException("Injected failure after debit");
  }
}
