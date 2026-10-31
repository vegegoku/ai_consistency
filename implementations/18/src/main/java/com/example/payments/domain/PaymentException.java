package com.example.payments.domain;
import java.util.*;
public class PaymentException extends RuntimeException {
    private final int http; private final String code; private final UUID paymentId; private final List<Issue> issues;
    public PaymentException(int http, String code) { this(http,code,null,List.of()); }
    public PaymentException(int http,String code,UUID paymentId,List<Issue> issues) {
        super(http>=500?"The service could not confirm the outcome. Query or retry using the same paymentId.":"The payment could not be completed.");
        this.http=http; this.code=code; this.paymentId=paymentId; this.issues=List.copyOf(issues);
    }
    public int http(){return http;} public String code(){return code;} public UUID paymentId(){return paymentId;} public List<Issue> issues(){return issues;}
}
