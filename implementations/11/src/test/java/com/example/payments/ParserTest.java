package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.ApiFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParserTest {
  final MessageParser parser = new MessageParser(65536, "10000.000", 300, 2592000);

  String json(String amount) {
    return "{\"paymentId\":\""
        + UUID.randomUUID()
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void usesDecimalAndTrimsText() {
    var c = parser.parse(json("125.750").getBytes(), "application/json");
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void rejectsExcessPrecisionAndCollectsIssues() {
    var ex =
        assertThrows(
            ApiFailure.class,
            () ->
                parser.parse(
                    json("0.0001").replace("\"A\"", "\"B\"").getBytes(), "application/json"));
    assertEquals("VALIDATION_FAILED", ex.code);
    assertTrue(ex.issues.size() >= 2);
  }

  @Test
  void rejectsDuplicateJson() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () -> parser.parse("{\"amount\":1,\"amount\":2}".getBytes(), "application/json"))
            .code);
  }

  @Test
  void rejectsXXEAndDuplicateXml() {
    for (String s :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment xmlns:x='urn:x'><x:amount>1</x:amount></payment>"
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiFailure.class, () -> parser.parse(s.getBytes(), "application/xml")).code);
  }

  @Test
  void rejectsWrongTypesUnknownFieldsAndBodySize() {
    for (String s :
        new String[] {
          json("true"),
          json("1").replace("\"fromAccount\":\"A\"", "\"fromAccount\":123"),
          "{\"extra\":1}"
        }) assertThrows(ApiFailure.class, () -> parser.parse(s.getBytes(), "application/json"));
    assertEquals(
        413,
        assertThrows(ApiFailure.class, () -> parser.parse(new byte[65537], "application/json"))
            .http);
  }

  @Test
  void retainsDecimalPrecision() {
    assertThrows(
        ApiFailure.class, () -> parser.parse(json("1.0000").getBytes(), "application/json"));
    assertThrows(
        ApiFailure.class,
        () -> parser.parse(json("0.10000000000000001").getBytes(), "application/json"));
  }

  @Test
  void formatIndependentFingerprint() {
    String j = json("125.750");
    var c = parser.parse(j.getBytes(), "application/json");
    String xml =
        "<payment><paymentId>"
            + c.paymentId()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>"
            + c.date()
            + "</date><purpose>invoice</purpose></payment>";
    assertEquals(c.fingerprint(), parser.parse(xml.getBytes(), "application/xml").fingerprint());
  }

  @Test
  void invalidAmountsAndOffsetlessDate() {
    for (String value : new String[] {"0", "-1", "10000.001", "1e10000000"})
      assertThrows(
          ApiFailure.class, () -> parser.parse(json(value).getBytes(), "application/json"));
    assertThrows(
        ApiFailure.class,
        () ->
            parser.parse(
                json("1")
                    .replaceAll("\"date\":\"[^\"]+\"", "\"date\":\"2026-10-28T12:00:00\"")
                    .getBytes(),
                "application/json"));
  }
}
