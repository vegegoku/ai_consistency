package com.example.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiFailure extends RuntimeException {
    public record Issue(String field, String code, String message) {}
    private final int http;
    private final String code;
    private final UUID paymentId;
    private final List<Issue> issues;
    private final String correlationId;
    private final Instant timestamp;
    public ApiFailure(int http, String code) { this(http, code, null, List.of(), null, null); }
    public ApiFailure(int http, String code, UUID paymentId, List<Issue> issues, String correlationId, Instant timestamp) {
        super("The request could not be completed."); this.http=http; this.code=code; this.paymentId=paymentId;
        this.issues=List.copyOf(issues); this.correlationId=correlationId; this.timestamp=timestamp;
    }
    public int http() { return http; } public String code() { return code; }
    public UUID paymentId() { return paymentId; } public List<Issue> issues() { return issues; }
    public String correlationId() { return correlationId; } public Instant timestamp() { return timestamp; }
}
