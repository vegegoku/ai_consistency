package com.example.payments.application;

import com.example.payments.domain.PaymentCommand;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;

/** Extension point for transaction instrumentation. Tests replace this bean to inject faults. */
@ApplicationScoped
public class TransferProbe {
    public void afterDebit(Connection connection, PaymentCommand command) {}
}
