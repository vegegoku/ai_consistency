package com.example.payments.application;

import jakarta.inject.Inject;

@jakarta.inject.Singleton
public class FailureInjector {
  @Inject Settings settings;
  public volatile String point = "";

  public void at(String at) {
    if (settings.failureInjection && point.equals(at))
      throw new IllegalStateException("Injected failure");
  }
}
