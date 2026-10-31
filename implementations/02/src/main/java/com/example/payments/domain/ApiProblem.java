package com.example.payments.domain;

import java.util.List;

public final class ApiProblem extends RuntimeException {
    public final int http;
    public final String code;
    public final String paymentId;
    public final List<Issue> issues;
    public ApiProblem(int http, String code, String paymentId, List<Issue> issues) {
        super(code); this.http=http; this.code=code; this.paymentId=paymentId; this.issues=List.copyOf(issues);
    }
    public ApiProblem(int http, String code) { this(http,code,null,List.of()); }
    public record Issue(String field, String code, String message) {}
}
