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
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (String s :
          new String[] {
            fromAccount, toAccount, amount.toPlainString(), date.toString(), purpose, notes
          }) {
        if (s == null) {
          out.writeInt(-1);
        } else {
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          out.writeInt(b.length);
          out.write(b);
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
