package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.PaymentException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  private final MessageParser parser =
      new MessageParser(
          65536,
          "10000.000",
          300,
          2592000,
          Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC));

  private String json(String amount) {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\"2026-10-28T13:30:00+03:00\",\"purpose\":\" invoice \"}";
  }

  @Test
  void equivalentFormatsShareCanonicalFingerprint() {
    var a = parser.parse(json("125.750").getBytes(), "application/json");
    var b =
        parser.parse(
            ("<payment><paymentId>"
                    + a.paymentId()
                    + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>invoice</purpose></payment>")
                .getBytes(),
            "application/xml");
    assertEquals("125.750", a.amount().toPlainString());
    assertEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  void rejectsDuplicateUnknownNestedAndWrongTypedValues() {
    for (var bad :
        List.of(
            json("1").replace("\"purpose\"", "\"unknown\""),
            json("1").replace("\"A\"", "12"),
            json("1").replace("\"purpose\":", "\"amount\":1,\"purpose\":"),
            json("{}"),
            json("true"),
            json("1") + "{}")) {
      var e =
          assertThrows(
              PaymentException.class, () -> parser.parse(bad.getBytes(), "application/json"));
      assertEquals("MALFORMED_MESSAGE", e.code());
    }
  }

  @Test
  void collectsAllFieldIssuesAndNeverRounds() {
    var e =
        assertThrows(
            PaymentException.class,
            () ->
                parser.parse(
                    json("-1.0001").replace("\"B\"", "\"A\"").replace(" invoice ", " ").getBytes(),
                    "application/json"));
    assertEquals("VALIDATION_FAILED", e.code());
    assertTrue(e.issues().size() >= 3);
  }

  @Test
  void rejectsDtdDuplicateElementsNamespacesAndNestedMarkup() {
    for (var bad :
        List.of(
            "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
            "<payment><amount>1</amount><amount>2</amount></payment>",
            "<payment xmlns='evil'/>",
            "<payment><purpose><x/></purpose></payment>"))
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(
                  PaymentException.class, () -> parser.parse(bad.getBytes(), "application/xml"))
              .code());
  }

  @Test
  void validatesDatePrecisionUuidAndMissingFields() {
    for (var bad :
        List.of(
            json("0"),
            json("0.0001"),
            json("10000.001"),
            json("1").replace("+03:00", ""),
            json("1").replace("2026-10-28", "2026-08-28"),
            json("1").replace("d92192df-f79a-4553-8120-5660d2eb1294", "bad"),
            "{}"))
      assertEquals(
          "VALIDATION_FAILED",
          assertThrows(
                  PaymentException.class, () -> parser.parse(bad.getBytes(), "application/json"))
              .code());
  }

  @Test
  void rejectsUnstorableNullAndUnpairedUnicodeWithoutDatabaseAccess() {
    for (var invalid : List.of("\\u0000", "\\uD800")) {
      var bad = json("1").replace(" invoice ", invalid);
      assertEquals(
          "VALIDATION_FAILED",
          assertThrows(
                  PaymentException.class, () -> parser.parse(bad.getBytes(), "application/json"))
              .code());
    }
  }
}
