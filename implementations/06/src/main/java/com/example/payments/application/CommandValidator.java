package com.example.payments.application;

import com.example.payments.domain.*;
import com.example.payments.domain.PaymentException.Issue;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.*;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class CommandValidator {
  @ConfigProperty(name = "payment.maximum", defaultValue = "10000.000")
  BigDecimal maximum;

  @ConfigProperty(name = "payment.max-age-seconds", defaultValue = "2592000")
  long maxAge;

  @ConfigProperty(name = "payment.future-seconds", defaultValue = "300")
  long future;

  public PaymentCommand validate(Map<String, String> v) {
    List<Issue> issues = new ArrayList<>();
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String field :
        List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (v.get(field) == null || v.get(field).isBlank())
        issues.add(new Issue(field, "REQUIRED", "A value is required."));
    if (v.get("paymentId") != null)
      try {
        if (!v.get("paymentId")
            .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new Exception();
        id = UUID.fromString(v.get("paymentId"));
      } catch (Exception e) {
        issues.add(new Issue("paymentId", "INVALID_UUID", "A canonical UUID is required."));
      }
    if (v.get("amount") != null)
      try {
        if (!v.get("amount").matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new Exception();
        amount = new BigDecimal(v.get("amount"));
        if (amount.signum() <= 0 || amount.scale() > 3 || amount.compareTo(maximum) > 0)
          throw new Exception();
        amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issues.add(
            new Issue(
                "amount",
                "INVALID_AMOUNT",
                "Use a positive decimal with at most 3 fractional digits within the configured"
                    + " maximum."));
      }
    if (v.get("date") != null)
      try {
        date = OffsetDateTime.parse(v.get("date")).toInstant();
      } catch (Exception e) {
        issues.add(
            new Issue("date", "INVALID_DATE", "An ISO 8601 timestamp with offset is required."));
      }
    for (String field : List.of("fromAccount", "toAccount"))
      if (v.get(field) != null && v.get(field).length() > 128)
        issues.add(new Issue(field, "TOO_LONG", "Maximum length is 128."));
    String purpose = v.get("purpose") == null ? null : v.get("purpose").strip();
    String notes = v.get("notes") == null ? "" : v.get("notes").strip();
    if (purpose != null && purpose.length() > 255)
      issues.add(new Issue("purpose", "TOO_LONG", "Maximum length is 255."));
    if (notes.length() > 1000)
      issues.add(new Issue("notes", "TOO_LONG", "Maximum length is 1000."));
    if (v.get("fromAccount") != null && v.get("fromAccount").equals(v.get("toAccount")))
      issues.add(new Issue("toAccount", "SAME_ACCOUNT", "Accounts must differ."));
    if (!issues.isEmpty()) {
      if (date != null && outsideWindow(date))
        issues.add(
            new Issue(
                "date",
                "DATE_OUT_OF_RANGE",
                "Instruction timestamp is outside the configured time window."));
      var e = new PaymentException(400, "VALIDATION_FAILED", issues);
      if (id != null) e.paymentId = id.toString();
      throw e;
    }
    return new PaymentCommand(
        id, v.get("fromAccount"), v.get("toAccount"), amount, date, purpose, notes);
  }

  private boolean outsideWindow(Instant date) {
    Instant now = Instant.now();
    return date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(maxAge));
  }

  public void validateTime(PaymentCommand c) {
    if (outsideWindow(c.date())) {
      var e =
          new PaymentException(
              400,
              "VALIDATION_FAILED",
              List.of(
                  new Issue(
                      "date",
                      "DATE_OUT_OF_RANGE",
                      "Instruction timestamp is outside the configured time window.")));
      e.paymentId = c.paymentId().toString();
      throw e;
    }
  }
}
