package com.example.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record PaymentRecord(String paymentId,String status,String fromAccount,String toAccount,BigDecimal amount,
                            Instant processedAt,String requestedBy,String fingerprint,String rejectionCode,int rejectionHttp,String correlationId) {
    public Map<String,Object> receipt() {
        Map<String,Object> result=new java.util.LinkedHashMap<>();
        result.put("paymentId",paymentId);result.put("status",status);result.put("fromAccount",fromAccount);result.put("toAccount",toAccount);
        result.put("amount",amount.setScale(3).toPlainString());result.put("currency","JOD");result.put("processedAt",processedAt.toString());
        return java.util.Collections.unmodifiableMap(result);
    }
    public ApiProblem rejection() { return new ApiProblem(rejectionHttp,rejectionCode,paymentId,java.util.List.of()); }
}
