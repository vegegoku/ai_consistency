package com.example.payments.domain;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
    try {
      var out = new ByteArrayOutputStream();
      try (var d = new DataOutputStream(out)) {
        for (String s :
            new String[] {
              fromAccount,
              toAccount,
              amount.setScale(3).toPlainString(),
              date.toString(),
              purpose,
              notes == null ? "" : notes
            }) {
          byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
          d.writeInt(bytes.length);
          d.write(bytes);
        }
      }
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
