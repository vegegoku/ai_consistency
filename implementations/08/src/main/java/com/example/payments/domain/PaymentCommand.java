package com.example.payments.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * All safe normalization happens before construction. Money and identities are never rounded or
 * trimmed.
 */
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
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
