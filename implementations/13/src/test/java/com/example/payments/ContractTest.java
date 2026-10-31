package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageCodec;
import com.example.payments.domain.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractTest {
  final MessageCodec codec = new MessageCodec();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC);

  String json(String amount) {
    return """
{"paymentId":"d92192df-f79a-4553-8120-5660d2eb1294","fromAccount":"A","toAccount":"B","amount":%s,"date":"2026-10-28T13:30:00+03:00","purpose":" invoice ","notes":" note "}
"""
        .formatted(amount);
  }

  @Test
  void canonicalFormatsAndAmounts() {
    var a =
        PaymentCommand.validate(
            codec.parse(json("125.750").getBytes(), false), clock, "10000", 300, 2592000);
    var b =
        PaymentCommand.validate(
            codec.parse(json("\"125.75\"").getBytes(), false), clock, "10000", 300, 2592000);
    assertEquals(a.fingerprint(), b.fingerprint());
    assertEquals("125.750", a.amount().toPlainString());
    assertEquals("invoice", a.purpose());
  }

  @Test
  void duplicateUnknownAndTypedPropertiesAreRejected() {
    for (String s :
        new String[] {
          json("true"),
          json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
          json("1").replace("\"notes\"", "\"unknown\"")
        }) assertThrows(ApiFailure.class, () -> codec.parse(s.getBytes(), false));
  }

  @Test
  void excessivePrecisionAndMissingFieldsCollectIssues() {
    var error =
        assertThrows(
            ApiFailure.class,
            () ->
                PaymentCommand.validate(
                    codec.parse("{\"amount\":0.0001}".getBytes(), false),
                    clock,
                    "10000",
                    300,
                    2592000));
    assertTrue(error.issues.size() >= 6);
    assertEquals("VALIDATION_FAILED", error.code);
  }

  @Test
  void xmlRejectsDtdDuplicateNamespacesAndNestedElements() {
    for (String s :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><notes>&x;</notes></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment xmlns='evil'><amount>1</amount></payment>",
          "<payment><notes><x/></notes></payment>"
        }) assertThrows(ApiFailure.class, () -> codec.parse(s.getBytes(), true));
  }

  @Test
  void xmlSharesCanonicalCommand() {
    var a =
        PaymentCommand.validate(
            codec.parse(json("125.750").getBytes(), false), clock, "10000", 300, 2592000);
    var xml =
        "<payment><paymentId>"
            + a.paymentId()
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>invoice</purpose><notes>note</notes></payment>";
    assertEquals(
        a.fingerprint(),
        PaymentCommand.validate(codec.parse(xml.getBytes(), true), clock, "10000", 300, 2592000)
            .fingerprint());
  }

  @Test
  void invalidAmountDateAndSameAccountAreRejected() {
    for (String amount : List.of("0", "-1", "10000.001", "0.0001"))
      assertThrows(
          ApiFailure.class,
          () ->
              PaymentCommand.validate(
                  codec.parse(json(amount).getBytes(), false), clock, "10000", 300, 2592000));
    for (String date :
        List.of("2026-10-28T13:30:00", "2026-10-28T10:36:00Z", "2026-09-01T10:30:00Z")) {
      String input = json("1").replace("2026-10-28T13:30:00+03:00", date);
      assertThrows(
          ApiFailure.class,
          () ->
              PaymentCommand.validate(
                  codec.parse(input.getBytes(), false), clock, "10000", 300, 2592000));
    }
    assertThrows(
        ApiFailure.class,
        () ->
            PaymentCommand.validate(
                codec.parse(
                    json("1").replace("\"toAccount\":\"B\"", "\"toAccount\":\"A\"").getBytes(),
                    false),
                clock,
                "10000",
                300,
                2592000));
  }
}
