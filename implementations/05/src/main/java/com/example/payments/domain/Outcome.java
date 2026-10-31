package com.example.payments.domain;

import java.util.Map;

public record Outcome(int httpStatus, Map<String, Object> body) {}
