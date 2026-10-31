package com.example.payments.domain;
import java.time.Instant;
import java.util.UUID;
public record StoredPayment(UUID paymentId,String status,String fromAccount,String toAccount,String amount,String currency,
 Instant processedAt,String rejectionCode,String requestedBy,String fingerprint,String correlationId) {}
