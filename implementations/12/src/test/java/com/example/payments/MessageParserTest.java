package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  final MessageParser parser =
      new MessageParser(
          Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC),
          new java.math.BigDecimal("10000.000"),
          300,
          2592000,
          65536);
  final String id = "d92192df-f79a-4553-8120-5660d2eb1294";

  String json(String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\"2026-10-28T13:30:00+03:00\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalEquivalentFormats() {
    var j = parser.parse(json("125.750").getBytes(), "application/json");
    var x =
        parser.parse(
            ("<payment><paymentId>"
                    + id
                    + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>invoice</purpose></payment>")
                .getBytes(),
            "application/xml");
    assertEquals("125.750", j.amount().toPlainString());
    assertEquals(j.fingerprint(), x.fingerprint());
  }

  @Test
  void rejectsDuplicateUnknownAndInvalidTypedFields() {
    for (String body :
        new String[] {
          json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
          json("true"),
          json("{}").replace("{}", "[]"),
          json("1").replace("\"purpose\"", "\"unknown\""),
          json("1") + "{}"
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiException.class, () -> parser.parse(body.getBytes(), "application/json"))
              .code());
  }

  @Test
  void collectsFieldIssuesWithoutRounding() {
    var e =
        assertThrows(
            ApiException.class,
            () ->
                parser.parse(
                    json("0.0001").replace("\"B\"", "\"A\"").replace("invoice", " ").getBytes(),
                    "application/json"));
    assertEquals("VALIDATION_FAILED", e.code());
    assertTrue(e.issues().size() >= 3);
  }

  @Test
  void rejectsDtdEntitiesNestedAndRepeatedXml() {
    for (String xml :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY secret SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&secret;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment><notes><nested>x</nested></notes></payment>",
          "<payment xmlns:x='urn:x'><x:notes>x</x:notes></payment>"
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiException.class, () -> parser.parse(xml.getBytes(), "application/xml"))
              .code());
  }

  @Test
  void requiresExactIdsAndOffsetDateAndBounds() {
    for (String body :
        new String[] {
          json("-1"),
          json("10000.001"),
          json("1").replace(id, "1-1-1-1-1"),
          json("1").replace("2026-10-28T13:30:00+03:00", "2026-10-28T10:30:00"),
          json("1").replace("2026-10-28T13:30:00+03:00", "2026-10-28T10:35:01Z"),
          json("1").replace("2026-10-28T13:30:00+03:00", "2026-09-27T10:30:00Z")
        })
      assertEquals(
          "VALIDATION_FAILED",
          assertThrows(ApiException.class, () -> parser.parse(body.getBytes(), "application/json"))
              .code());
  }
}
