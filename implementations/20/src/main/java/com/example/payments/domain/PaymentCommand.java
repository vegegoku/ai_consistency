package com.example.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCommand(
    UUID paymentId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    Instant date,
    String purpose,
    String notes) {}
