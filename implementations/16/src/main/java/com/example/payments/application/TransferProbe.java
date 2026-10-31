package com.example.payments.application;

/**
 * Extension point for instrumenting transaction stages; implementations must never call external
 * systems.
 */
@FunctionalInterface
public interface TransferProbe {
  void afterDebit();
}
