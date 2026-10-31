package com.example.payments.domain;

public record Receipt(
    String paymentId,
    String status,
    String fromAccount,
    String toAccount,
    String amount,
    String currency,
    String processedAt) {}
