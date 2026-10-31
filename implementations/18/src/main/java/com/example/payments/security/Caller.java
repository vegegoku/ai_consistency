package com.example.payments.security;
import jakarta.enterprise.context.RequestScoped;
import java.util.*;
@RequestScoped
public class Caller {
    public String principal; public String correlationId=UUID.randomUUID().toString(); public long started=System.nanoTime();
}
