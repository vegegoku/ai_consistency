package com.example.payments.application;

/** Constructor-only test seam; there is deliberately no request-controlled fault switch. */
@FunctionalInterface
public interface FaultInjector {
  void afterDebit();

  FaultInjector NONE = () -> {};
}
