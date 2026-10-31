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
      var d = MessageDigest.getInstance("SHA-256");
      for (String s :
          new String[] {
            paymentId.toString(),
            fromAccount,
            toAccount,
            amount.toPlainString(),
            date.toString(),
            purpose,
            notes == null ? "" : notes
          }) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
        d.update(b);
      }
      return HexFormat.of().formatHex(d.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
