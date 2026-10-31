package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.ApiFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  final MessageParser parser = new MessageParser(65536, "10000.000", 300, 2592000);

  String json(String amount) {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalDecimalAndTrim() {
    var c = parser.parse(json("125.75").getBytes(), "application/json");
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void rejectsExcessPrecision() {
    assertEquals(
        "VALIDATION_FAILED",
        assertThrows(
                ApiFailure.class, () -> parser.parse(json("1.0000").getBytes(), "application/json"))
            .code());
  }

  @Test
  void rejectsDuplicateJson() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () ->
                    parser.parse(
                        json("1").replace("\"purpose\":", "\"amount\":2,\"purpose\":").getBytes(),
                        "application/json"))
            .code());
  }

  @Test
  void rejectsUnknownAndWrongTypes() {
    assertThrows(ApiFailure.class, () -> parser.parse(json("true").getBytes(), "application/json"));
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                json("1").replace("\"purpose\":", "\"unknown\":1,\"purpose\":").getBytes(),
                "application/json"));
  }

  @Test
  void rejectsDtdAndDuplicateXml() {
    for (String x :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY e SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&e;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment xmlns:x='evil'><x:amount>1</x:amount></payment>"
        }) assertThrows(ApiFailure.class, () -> parser.parse(x.getBytes(), "application/xml"));
  }

  @Test
  void collectsValidationIssues() {
    var e = assertThrows(ApiFailure.class, () -> parser.parse("{}".getBytes(), "application/json"));
    assertTrue(e.issues().size() >= 6);
  }

  @Test
  void validatesNegativeZeroMaximumAndExponentAmounts() {
    for (String amount : new String[] {"0", "-1", "10000.001", "1e3", "\"NaN\""})
      assertEquals(
          "VALIDATION_FAILED",
          assertThrows(
                  ApiFailure.class, () -> parser.parse(json(amount).getBytes(), "application/json"))
              .code());
  }

  @Test
  void validatesDatesAndSameAccount() {
    for (String date :
        new String[] {
          "2026-10-28T10:00:00",
          Instant.now().plusSeconds(600).toString(),
          Instant.now().minusSeconds(2592100).toString()
        }) {
      String input = json("1").replaceAll("\"date\":\"[^\"]+\"", "\"date\":\"" + date + "\"");
      assertThrows(ApiFailure.class, () -> parser.parse(input.getBytes(), "application/json"));
    }
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                json("1").replace("\"toAccount\":\"B\"", "\"toAccount\":\"A\"").getBytes(),
                "application/json"));
  }

  @Test
  void boundsBodyAndXmlDepth() {
    assertEquals(
        413,
        assertThrows(ApiFailure.class, () -> parser.parse(new byte[65537], "application/json"))
            .http());
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                "<payment><notes><nested>bad</nested></notes></payment>".getBytes(),
                "application/xml"));
  }
}
