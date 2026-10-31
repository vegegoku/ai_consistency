package com.example.payments.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record PaymentCommand(
    UUID paymentId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    Instant date,
    String purpose,
    String notes) {
  public String fingerprint() {
    StringBuilder b = new StringBuilder();
    for (String v :
        new String[] {
          fromAccount, toAccount, amount.toPlainString(), date.toString(), purpose, notes
        }) {
      String s = v == null ? "" : v;
      b.append(s.length()).append(':').append(s);
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
