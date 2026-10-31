package com.example.payments.application;

import jakarta.inject.Singleton;
import java.math.BigDecimal;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public class Limits {
  @ConfigProperty(name = "payment.maximum", defaultValue = "10000.000")
  public BigDecimal maximum;

  @ConfigProperty(name = "payment.future-seconds", defaultValue = "300")
  public long futureSeconds;

  @ConfigProperty(name = "payment.age-seconds", defaultValue = "2592000")
  public long ageSeconds;

  @ConfigProperty(name = "payment.body-bytes", defaultValue = "65536")
  public int bodyBytes;

  @ConfigProperty(name = "payment.rate-per-minute", defaultValue = "600")
  public int rate;

  @ConfigProperty(name = "payment.db-lock-ms", defaultValue = "2000")
  public int lockMs;

  @ConfigProperty(name = "payment.db-statement-ms", defaultValue = "5000")
  public int statementMs;

  @ConfigProperty(name = "payment.retries", defaultValue = "2")
  public int retries;

  @ConfigProperty(name = "payment.require-tls", defaultValue = "true")
  public boolean requireTls;

  @ConfigProperty(name = "payment.audit-retention-days", defaultValue = "2555")
  public int retentionDays;

  @jakarta.annotation.PostConstruct
  void validate() {
    if (maximum.signum() <= 0
        || maximum.scale() > 3
        || maximum.setScale(3).precision() > 19
        || futureSeconds < 0
        || ageSeconds <= 0
        || bodyBytes <= 0
        || rate <= 0
        || lockMs <= 0
        || statementMs <= 0
        || retries < 0
        || retries > 10
        || retentionDays <= 0)
      throw new IllegalArgumentException("Invalid payment policy configuration");
  }
}
