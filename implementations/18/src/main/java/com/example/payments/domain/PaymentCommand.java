package com.example.payments.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.security.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
public record PaymentCommand(UUID paymentId,String fromAccount,String toAccount,BigDecimal amount,Instant date,String purpose,String notes) {
    public String fingerprint() {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:new String[]{paymentId.toString(),fromAccount,toAccount,amount.setScale(3).toPlainString(),date.toString(),purpose,notes}) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
