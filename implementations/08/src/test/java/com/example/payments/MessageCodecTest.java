package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class MessageCodecTest {
  final MessageCodec codec = new MessageCodec();
  final String id = "d92192df-f79a-4553-8120-5660d2eb1294";

  String json(String amount) {
    return "{\"paymentId\":\""
        + id
        + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":"
        + amount
        + ",\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void parsesExactMoneyAndNormalizesOnlyText() {
    var p = codec.parse(json("125.750").getBytes(), "application/json");
    assertEquals("125.750", p.amount().toPlainString());
    assertEquals("invoice", p.purpose());
  }

  @Test
  void rejectsDuplicateJsonAndUnknownFields() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () ->
                    codec.parse(
                        json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2").getBytes(),
                        "application/json"))
            .code);
    assertThrows(
        ApiFailure.class,
        () ->
            codec.parse(
                json("1").replace("\"purpose\"", "\"unexpected\"").getBytes(), "application/json"));
  }

  @Test
  void rejectsCoercionAndExtraPrecision() {
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class, () -> codec.parse(json("true").getBytes(), "application/json"))
            .code);
    assertEquals(
        "VALIDATION_FAILED",
        assertThrows(
                ApiFailure.class, () -> codec.parse(json("1.0001").getBytes(), "application/json"))
            .code);
  }

  @Test
  void rejectsExcessTrailingZeroPrecision() {
    assertThrows(
        ApiFailure.class, () -> codec.parse(json("1.0000").getBytes(), "application/json"));
  }

  @Test
  void collectsFieldFailures() {
    var ex = assertThrows(ApiFailure.class, () -> codec.parse("{}".getBytes(), "application/json"));
    assertTrue(ex.issues.size() >= 6);
  }

  @Test
  void rejectsXmlDoctypeExternalEntitiesDuplicatesAndNesting() {
    for (String x :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment><purpose><nested/></purpose></payment>",
          "<payment xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include"
              + " href='file:///etc/passwd'/></payment>"
        })
      assertEquals(
          "MALFORMED_MESSAGE",
          assertThrows(ApiFailure.class, () -> codec.parse(x.getBytes(), "application/xml")).code);
  }

  @Test
  void equivalentXmlAndJsonProduceSameFingerprint() {
    String j = json("1.0");
    var a = codec.parse(j.getBytes(), "application/json");
    String x =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>1.000</amount><date>"
            + a.date()
            + "</date><purpose>invoice</purpose></payment>";
    assertEquals(a.fingerprint(), codec.parse(x.getBytes(), "application/xml").fingerprint());
  }

  @Test
  void enforcesTimestampOffsetLimitsAndUuidFormat() {
    for (String value :
        new String[] {
          "2026-01-01T10:00:00",
          Instant.now().plusSeconds(400).toString(),
          Instant.now().minusSeconds(31 * 86400).toString()
        })
      assertThrows(
          ApiFailure.class,
          () ->
              codec.validateFreshness(
                  codec.parse(
                      json("1").replaceFirst("\"date\":\"[^\"]+", "\"date\":\"" + value).getBytes(),
                      "application/json")));
    assertThrows(
        ApiFailure.class,
        () -> codec.parse(json("1").replace(id, "1-1-1-1-1").getBytes(), "application/json"));
  }

  @Test
  void aggregatesStaleTimestampWithOtherInvalidFields() {
    String invalid =
        json("0")
            .replaceFirst(
                "\"date\":\"[^\"]+", "\"date\":\"" + Instant.now().minusSeconds(31 * 86400));
    var error =
        assertThrows(ApiFailure.class, () -> codec.parse(invalid.getBytes(), "application/json"));
    assertTrue(error.issues.stream().anyMatch(issue -> issue.field().equals("date")));
    assertTrue(error.issues.stream().anyMatch(issue -> issue.field().equals("amount")));
  }

  @Test
  void rejectsZeroNegativeLimitAndSameAccounts() {
    for (String amount : new String[] {"0", "-1", "10000.001"})
      assertThrows(
          ApiFailure.class, () -> codec.parse(json(amount).getBytes(), "application/json"));
    assertThrows(
        ApiFailure.class,
        () ->
            codec.parse(
                json("1").replace("\"toAccount\":\"B\"", "\"toAccount\":\"A\"").getBytes(),
                "application/json"));
  }

  @Test
  void rejectsLoneUnicodeSurrogateRatherThanReplacingFingerprintBytes() {
    String surrogateEscape = "\\" + "uD800";
    assertEquals(
        "MALFORMED_MESSAGE",
        assertThrows(
                ApiFailure.class,
                () ->
                    codec.parse(
                        json("1").replace("invoice", surrogateEscape).getBytes(),
                        "application/json"))
            .code);
  }
}
