package com.example.payments.domain;

import java.time.Instant;
import java.util.UUID;

public record Outcome(
    UUID paymentId,
    String status,
    String fromAccount,
    String toAccount,
    String amount,
    String currency,
    Instant processedAt,
    String code,
    boolean replay) {}
