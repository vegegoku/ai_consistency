package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ParserTest {
  final MessageParser parser =
      new MessageParser(
          65536, new java.math.BigDecimal("10000.000"), Duration.ofDays(30), Duration.ofMinutes(5));

  String json(String amount) {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + OffsetDateTime.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalizesOnlySafeFields() {
    var c = parser.parse(json("125.750").getBytes(), "application/json");
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void rejectsPrecisionAndCollectsIssues() {
    var e =
        assertThrows(
            PaymentException.class,
            () ->
                parser.parse(
                    json("-1.0001").replace("\"B\"", "\"A\"").getBytes(), "application/json"));
    assertTrue(e.issues().size() >= 2);
  }

  @Test
  void rejectsDuplicateKeys() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                PaymentException.class,
                () ->
                    parser.parse(
                        json("1").replace("{", "{\"purpose\":\"x\",").getBytes(),
                        "application/json"))
            .code());
  }

  @Test
  void rejectsTypedCoercion() {
    assertThrows(
        PaymentException.class,
        () -> parser.parse(json("1").replace("\"A\"", "42").getBytes(), "application/json"));
  }

  @Test
  void rejectsDtd() {
    assertThrows(
        PaymentException.class,
        () ->
            parser.parse(
                "<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment>&x;</payment>"
                    .getBytes(),
                "application/xml"));
  }

  @Test
  void rejectsDuplicateXml() {
    assertThrows(
        PaymentException.class,
        () ->
            parser.parse(
                "<payment><amount>1</amount><amount>2</amount></payment>".getBytes(),
                "application/xml"));
  }

  @Test
  void rejectsOversize() {
    assertEquals(
        413,
        assertThrows(
                PaymentException.class, () -> parser.parse(new byte[65537], "application/json"))
            .http());
  }

  @Test
  void rejectsZeroNegativeExcessiveAndMalformedAmounts() {
    for (String amount : java.util.List.of("0", "-1", "10000.001", "1.0000", "true", "\"1e2\""))
      assertThrows(
          PaymentException.class, () -> parser.parse(json(amount).getBytes(), "application/json"));
  }

  @Test
  void rejectsMissingUnknownAndInvalidDate() {
    assertThrows(PaymentException.class, () -> parser.parse("{}".getBytes(), "application/json"));
    assertThrows(
        PaymentException.class,
        () ->
            parser.parse(
                json("1")
                    .replace("invoice", "invoice")
                    .replace("\"purpose\"", "\"unexpected\"")
                    .getBytes(),
                "application/json"));
    for (String invalid :
        java.util.List.of(
            "not-a-date", "2026-10-28T12:00:00", "2000-01-01T00:00:00Z", "2100-01-01T00:00:00Z")) {
      String body = json("1").replaceFirst("\"date\":\"[^\"]+\"", "\"date\":\"" + invalid + "\"");
      assertThrows(PaymentException.class, () -> parser.parse(body.getBytes(), "application/json"));
    }
  }
}
