package com.example.payments.domain;

import java.util.Map;

public record PaymentResult(int http, boolean replay, Map<String, Object> body) {}
