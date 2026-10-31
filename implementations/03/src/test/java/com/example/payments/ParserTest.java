package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.PaymentParser;
import com.example.payments.domain.ApiFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ParserTest {
  final PaymentParser parser = new PaymentParser();

  String json(String amount) {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalDecimalAndTrim() {
    var p = parser.parse(json("125.750"), "application/json");
    assertEquals("125.750", p.amount().toPlainString());
    assertEquals("invoice", p.purpose());
  }

  @Test
  void rejectsPrecision() {
    assertEquals(
        "VALIDATION_FAILED",
        assertThrows(ApiFailure.class, () -> parser.parse(json("1.0001"), "application/json"))
            .code);
  }

  @Test
  void rejectsDuplicates() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () ->
                    parser.parse(
                        json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
                        "application/json"))
            .code);
  }

  @Test
  void rejectsDtd() {
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                    + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
                "application/xml"));
  }

  @Test
  void lexicalDecimalPrecision() {
    assertThrows(ApiFailure.class, () -> parser.parse(json("1.0000"), "application/json"));
  }

  @Test
  void xmlDuplicatesAndNestedContent() {
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                "<payment><amount>1</amount><amount>2</amount></payment>", "application/xml"));
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                "<payment><purpose><nested>x</nested></purpose></payment>", "application/xml"));
  }

  @Test
  void rejectsUnknownAndTypedFields() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () ->
                    parser.parse(
                        json("1").replace("\"fromAccount\":\"A\"", "\"fromAccount\":12"),
                        "application/json"))
            .code);
    assertThrows(
        ApiFailure.class,
        () -> parser.parse(json("1").replace("\"purpose\"", "\"unknown\""), "application/json"));
  }

  @Test
  void collectsIssues() {
    assertTrue(
        assertThrows(ApiFailure.class, () -> parser.parse("{}", "application/json")).issues.size()
            >= 6);
  }
}
