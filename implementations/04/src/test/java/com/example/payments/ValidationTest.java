package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.PaymentParser;
import com.example.payments.domain.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValidationTest {
  final ObjectMapper json = new ObjectMapper();
  final PaymentParser parser = new PaymentParser();

  Map<String, Object> valid() {
    var m = new LinkedHashMap<String, Object>();
    m.put("paymentId", UUID.randomUUID().toString());
    m.put("fromAccount", "A");
    m.put("toAccount", "B");
    m.put("amount", "1.000");
    m.put("date", Instant.now().toString());
    m.put("purpose", "Invoice");
    return m;
  }

  ApiException invalid(String field, Object value) throws Exception {
    var m = valid();
    m.put(field, value);
    return assertThrows(ApiException.class, () -> parser.parse(json.writeValueAsBytes(m), false));
  }

  @Test
  void rejectsInvalidMoneyWithoutRounding() throws Exception {
    for (String v : List.of("0", "-1", "1.0001", "10000.001", "NaN", "1e2", " 1", "+1"))
      assertEquals("VALIDATION_FAILED", invalid("amount", v).code);
  }

  @Test
  void rejectsUnboundedOrOffsetlessTimestamps() throws Exception {
    for (String v :
        List.of(
            "2026-10-28",
            "2026-10-28T10:00:00",
            "tomorrow",
            Instant.now().plusSeconds(301).toString(),
            Instant.now().minus(Duration.ofDays(31)).toString()))
      assertEquals("VALIDATION_FAILED", invalid("date", v).code);
  }

  @Test
  void rejectsInvalidIdentityLengthsAndSameAccounts() throws Exception {
    assertEquals("VALIDATION_FAILED", invalid("paymentId", "1-1-1-1-1").code);
    assertEquals("VALIDATION_FAILED", invalid("fromAccount", " A").code);
    assertEquals("VALIDATION_FAILED", invalid("toAccount", "A").code);
    assertEquals("VALIDATION_FAILED", invalid("purpose", "x".repeat(256)).code);
    assertEquals("VALIDATION_FAILED", invalid("notes", "x".repeat(1001)).code);
    assertEquals("VALIDATION_FAILED", invalid("notes", "\u0000").code);
  }

  @Test
  void validationErrorKeepsOnlyValidPaymentIdentity() throws Exception {
    assertNotNull(invalid("amount", "0").paymentId);
    assertNull(invalid("paymentId", "bad").paymentId);
  }

  @Test
  void offsetAndScaleCanonicalizationPreservesFingerprint() throws Exception {
    var a = valid();
    var b = new LinkedHashMap<>(a);
    var now = Instant.now();
    a.put("date", now.toString());
    b.put("date", now.atOffset(ZoneOffset.ofHours(3)).toString());
    a.put("amount", "1");
    b.put("amount", "1.000");
    a.put("notes", "");
    assertEquals(
        parser.parse(json.writeValueAsBytes(a), false).fingerprint(),
        parser.parse(json.writeValueAsBytes(b), false).fingerprint());
  }
}
