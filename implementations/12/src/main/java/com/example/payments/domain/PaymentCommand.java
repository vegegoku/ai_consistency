package com.example.payments.domain;

import java.io.*;
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
      var buffer = new ByteArrayOutputStream();
      var out = new DataOutputStream(buffer);
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
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
    } catch (java.security.NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
