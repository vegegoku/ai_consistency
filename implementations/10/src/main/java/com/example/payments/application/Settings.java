package com.example.payments.application;

import java.math.BigDecimal;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@jakarta.inject.Singleton
public class Settings {
  @ConfigProperty(name = "payments.max-amount")
  public BigDecimal maxAmount;

  @ConfigProperty(name = "payments.max-body-bytes")
  public int maxBody;

  @ConfigProperty(name = "payments.max-age-seconds")
  public long maxAge;

  @ConfigProperty(name = "payments.future-seconds")
  public long future;

  @ConfigProperty(name = "payments.rate-per-minute")
  public int rate;

  @ConfigProperty(name = "payments.require-tls")
  public boolean tls;

  @ConfigProperty(name = "payments.failure-injection")
  public boolean failureInjection;
}
