package com.example.payments;

import com.example.payments.application.TransferFault;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative
@Priority(1)
@ApplicationScoped
public class FailingHook extends TransferFault {
  public static volatile boolean fail;

  @Override
  public void afterDebit() {
    if (fail) throw new IllegalStateException("Injected transaction failure");
  }
}
