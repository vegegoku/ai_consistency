package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.Codec;
import com.example.payments.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class CodecTest {
  final Codec codec =
      new Codec(
          65536,
          "10000.000",
          Duration.ofDays(30),
          Duration.ofMinutes(5),
          Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC));

  static String json(String amount) {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"
        + amount
        + ",\"date\":\"2026-10-28T13:30:00+03:00\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalEquivalentAcrossFormats() {
    var a = codec.parse(json("125.750").getBytes(), "application/json");
    var b =
        codec.parse(
            ("<payment><paymentId>"
                    + a.paymentId()
                    + "</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>invoice</purpose></payment>")
                .getBytes(),
            "application/xml");
    assertEquals(a, b);
    assertEquals(a.fingerprint(), b.fingerprint());
    assertEquals("125.750", a.amount().toPlainString());
  }

  @Test
  void rejectsPrecisionAndCollectsIssues() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                codec.parse(
                    json("0.0001").replace(" invoice ", " ").getBytes(), "application/json"));
    assertEquals("VALIDATION_FAILED", ex.code);
    assertTrue(ex.issues.size() >= 2);
  }

  @Test
  void duplicateUnknownTypedAndTrailingDocumentsRejected() {
    for (var body :
        new String[] {
          json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
          json("true"),
          json("1") + " {}",
          json("1").replace("\"amount\":1", "\"extra\":1,\"amount\":1")
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiException.class, () -> codec.parse(body.getBytes(), "application/json"))
              .code);
  }

  @Test
  void rejectsXmlDtdDuplicateNestedAndNamespaces() {
    for (var body :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><notes>&x;</notes></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment><notes><a>x</a></notes></payment>",
          "<payment xmlns='urn:evil'/>",
          "<payment/><payment/>"
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiException.class, () -> codec.parse(body.getBytes(), "application/xml"))
              .code);
  }

  @Test
  void invalidIdentityAndTimeAggregated() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                codec.parse(
                    json("-1")
                        .replace("d92192df-f79a-4553-8120-5660d2eb1294", "1-1-1-1-1")
                        .replace("2026-10-28T13:30:00+03:00", "2026-10-28T13:30:00")
                        .getBytes(),
                    "application/json"));
    assertEquals(3, ex.issues.size());
    assertNull(ex.paymentId);
  }

  @Test
  void bodyAndMediaLimits() {
    assertEquals(
        413,
        assertThrows(ApiException.class, () -> codec.parse(new byte[65537], "application/json"))
            .http);
    assertEquals(
        415, assertThrows(ApiException.class, () -> codec.parse(new byte[0], "text/plain")).http);
  }

  @Test
  void collectsTemporalAndDescriptionErrors() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                codec.parse(
                    json("0")
                        .replace(" invoice ", " ")
                        .replace("2026-10-28T13:30:00+03:00", "2026-10-29T13:30:00+03:00")
                        .getBytes(),
                    "application/json"));
    assertEquals(3, ex.issues.size());
  }

  @Test
  void rejectsControlTextAndNegotiatesQuality() {
    assertThrows(
        ApiException.class,
        () ->
            codec.parse(
                json("1").replace(" invoice ", "bad\\u0000text").getBytes(), "application/json"));
    assertTrue(
        com.example.payments.api.ApiServer.negotiate(
            "application/json;q=0.5, application/xml;q=1"));
    assertFalse(
        com.example.payments.api.ApiServer.negotiate("application/json, application/xml;q=0"));
    assertThrows(
        ApiException.class,
        () ->
            com.example.payments.api.ApiServer.negotiate(
                "application/xml;q=0,application/json;q=0"));
  }
}
