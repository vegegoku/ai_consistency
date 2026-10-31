package com.example.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** The shared canonical instruction used for either wire format. */
public record PaymentCommand(String paymentId, String fromAccount, String toAccount, BigDecimal amount,
                             Instant date, String purpose, String notes) {
    public String fingerprint() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{fromAccount,toAccount,amount.toPlainString(),date.toString(),purpose,notes}) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
