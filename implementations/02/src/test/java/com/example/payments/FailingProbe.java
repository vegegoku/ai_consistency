package com.example.payments;

import com.example.payments.application.TransferProbe;
import com.example.payments.domain.PaymentCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

@Alternative @Priority(1) @ApplicationScoped
public class FailingProbe extends TransferProbe {
    public static final AtomicBoolean FAIL=new AtomicBoolean();
    @Override public void afterDebit(Connection c,PaymentCommand p){if(FAIL.getAndSet(false))throw new IllegalStateException("Injected after-debit failure");}
}
