package com.example.payments.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;

public record PaymentCommand(
    String paymentId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    Instant date,
    String purpose,
    String notes) {
  public String fingerprint() {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String field :
          new String[] {
            fromAccount,
            toAccount,
            amount.toPlainString(),
            date.toString(),
            purpose,
            notes == null ? "" : notes
          }) {
        byte[] b = field.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
        digest.update(b);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
