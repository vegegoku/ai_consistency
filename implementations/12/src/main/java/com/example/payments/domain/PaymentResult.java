package com.example.payments.domain;

import java.util.UUID;

public record PaymentResult(
    Receipt receipt, String rejectionCode, int rejectionHttp, boolean replay) {
  public void requireCompleted() {
    if (rejectionCode != null)
      throw new ApiException(
          rejectionHttp,
          rejectionCode,
          "The payment could not be completed.",
          UUID.fromString(receipt.paymentId()),
          java.util.List.of(
              new Issue("payment", rejectionCode, "The payment could not be completed.")),
          true);
  }
}
