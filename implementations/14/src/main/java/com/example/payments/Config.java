package com.example.payments;

import java.time.Duration;

public record Config(
    String jdbcUrl,
    String dbUser,
    String dbPassword,
    int port,
    boolean development,
    String issuer,
    String audience,
    String jwksFile,
    int bodyLimit,
    String maxAmount,
    Duration maxAge,
    Duration futureSkew,
    int rateLimit,
    String tlsStore,
    String tlsPassword,
    int retentionDays) {
  public static Config environment() {
    return new Config(
        env("DB_URL", "jdbc:postgresql://localhost:5432/payments"),
        env("DB_USER", "payments"),
        env("DB_PASSWORD", ""),
        Integer.parseInt(env("PORT", "8080")),
        Boolean.parseBoolean(env("DEV_MODE", "false")),
        env("JWT_ISSUER", "https://identity.example.invalid"),
        env("JWT_AUDIENCE", "payments"),
        env("JWT_JWKS_FILE", "config/public-jwks.json"),
        Integer.parseInt(env("MAX_BODY_BYTES", "65536")),
        env("MAX_PAYMENT_AMOUNT", "10000.000"),
        Duration.ofDays(Long.parseLong(env("MAX_AGE_DAYS", "30"))),
        Duration.ofSeconds(Long.parseLong(env("FUTURE_SKEW_SECONDS", "300"))),
        Integer.parseInt(env("RATE_LIMIT_PER_MINUTE", "600")),
        env("TLS_KEYSTORE", ""),
        env("TLS_PASSWORD", ""),
        Integer.parseInt(env("AUDIT_RETENTION_DAYS", "2555")));
  }

  private static String env(String key, String fallback) {
    return System.getenv().getOrDefault(key, fallback);
  }
}
