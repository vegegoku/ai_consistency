package com.example.payments.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value :
          new String[] {
            paymentId.toString(),
            fromAccount,
            toAccount,
            amount.toPlainString(),
            date.toString(),
            purpose,
            notes
          }) {
        if (value == null) {
          digest.update((byte) 0);
          continue;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
