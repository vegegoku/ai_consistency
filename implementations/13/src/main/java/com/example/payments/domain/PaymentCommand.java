package com.example.payments.domain;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public record PaymentCommand(
    UUID paymentId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    Instant date,
    String purpose,
    String notes) {
  public String fingerprint() {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      for (String s :
          Arrays.asList(
              fromAccount, toAccount, amount.toPlainString(), date.toString(), purpose, notes)) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
        md.update(b);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static PaymentCommand validate(
      Map<String, String> m, Clock clock, String maximum, long future, long age) {
    var issues = new ArrayList<ApiFailure.Issue>();
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (m.get(f) == null || m.get(f).isBlank())
        issue(issues, f, "REQUIRED", "A value is required.");
    if (m.get("paymentId") != null)
      try {
        String s = m.get("paymentId");
        id = UUID.fromString(s);
        if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
      } catch (Exception e) {
        id = null;
        issue(issues, "paymentId", "INVALID_UUID", "A canonical UUID is required.");
      }
    if (m.get("amount") != null)
      try {
        String s = m.get("amount");
        if (!s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new IllegalArgumentException();
        amount = new BigDecimal(s);
        if (amount.signum() <= 0
            || amount.scale() > 3
            || amount.compareTo(new BigDecimal(maximum)) > 0) throw new IllegalArgumentException();
        amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issue(
            issues,
            "amount",
            "INVALID_AMOUNT",
            "Amount must be positive, within the maximum, and have at most three decimal places.");
      }
    if (m.get("date") != null)
      try {
        date = OffsetDateTime.parse(m.get("date")).toInstant();
        if (date.isAfter(clock.instant().plusSeconds(future))
            || date.isBefore(clock.instant().minusSeconds(age)))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        issue(
            issues,
            "date",
            "INVALID_DATE",
            "An offset timestamp within the allowed age and future window is required.");
      }
    for (String f : List.of("fromAccount", "toAccount"))
      if (m.get(f) != null && m.get(f).length() > 128)
        issue(issues, f, "TOO_LONG", "Maximum length is 128.");
    if (m.get("fromAccount") != null && m.get("fromAccount").equals(m.get("toAccount")))
      issue(issues, "toAccount", "SAME_ACCOUNT", "Accounts must differ.");
    String purpose = m.get("purpose") == null ? null : m.get("purpose").trim(),
        notes = m.get("notes") == null ? "" : m.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      issue(issues, "purpose", "TOO_LONG", "Maximum length is 255.");
    if (notes.length() > 1000) issue(issues, "notes", "TOO_LONG", "Maximum length is 1000.");
    if (!issues.isEmpty())
      throw new ApiFailure(400, "VALIDATION_FAILED", issues)
          .identified(id == null ? null : id.toString());
    return new PaymentCommand(
        id, m.get("fromAccount"), m.get("toAccount"), amount, date, purpose, notes);
  }

  private static void issue(
      List<ApiFailure.Issue> list, String field, String code, String message) {
    list.add(new ApiFailure.Issue(field, code, message));
  }
}
