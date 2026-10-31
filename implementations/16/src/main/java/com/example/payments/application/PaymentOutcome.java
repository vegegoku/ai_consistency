package com.example.payments.application;

import java.util.Map;

public record PaymentOutcome(int http, Map<String, Object> body, boolean replay) {}
