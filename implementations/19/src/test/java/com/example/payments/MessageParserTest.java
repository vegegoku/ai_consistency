package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  final MessageParser parser =
      new MessageParser(new BigDecimal("10000.000"), Duration.ofDays(30), Duration.ofMinutes(5));

  String valid() {
    return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":125.750,\"date\":\""
        + Instant.now()
        + "\",\"purpose\":\" invoice \"}";
  }

  @Test
  void canonicalMoneyAndSafeNormalization() {
    var c = parser.parse(valid().getBytes(), false);
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void duplicateAndUnknownFieldsRejected() {
    assertThrows(
        ApiException.class,
        () ->
            parser.parse(
                valid().replace("\"amount\":125.750", "\"amount\":1,\"amount\":2").getBytes(),
                false));
    assertThrows(
        ApiException.class,
        () -> parser.parse(valid().replace("\"purpose\"", "\"unknown\"").getBytes(), false));
  }

  @Test
  void aggregateInvalidFields() {
    var e =
        assertThrows(
            ApiException.class, () -> parser.parse("{\"amount\":-1.0001}".getBytes(), false));
    assertTrue(e.issues.size() >= 6);
  }

  @Test
  void xmlEntityAndDuplicateRejected() {
    assertThrows(
        ApiException.class,
        () ->
            parser.parse(
                "<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>"
                    .getBytes(),
                true));
    assertThrows(
        ApiException.class,
        () ->
            parser.parse(
                "<payment><amount>1</amount><amount>2</amount></payment>".getBytes(), true));
  }

  @Test
  void typedValuesNotCoerced() {
    assertThrows(
        ApiException.class, () -> parser.parse(valid().replace("\"A\"", "123").getBytes(), false));
  }

  @Test
  void precisionAmountsDatesAndSameAccountHaveDocumentedIssues() {
    var cases =
        Map.of(
            "0", "POSITIVE_REQUIRED",
            "-1", "POSITIVE_REQUIRED",
            "1.0000", "EXCESS_PRECISION",
            "10000.001", "PAYMENT_LIMIT_EXCEEDED");
    cases.forEach(
        (amount, expected) -> {
          var e =
              assertThrows(
                  ApiException.class,
                  () -> parser.parse(valid().replace("125.750", amount).getBytes(), false));
          assertTrue(
              e.issues.stream()
                  .anyMatch(i -> i.field().equals("amount") && i.code().equals(expected)));
        });
    var same =
        assertThrows(
            ApiException.class,
            () -> parser.parse(valid().replace("\"B\"", "\"A\"").getBytes(), false));
    assertTrue(same.issues.stream().anyMatch(i -> i.code().equals("SAME_ACCOUNT")));
    for (String date :
        List.of("2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", "2026-10-28T12:00:00")) {
      String input = valid().replaceFirst("\"date\":\"[^\"]+\"", "\"date\":\"" + date + "\"");
      var e = assertThrows(ApiException.class, () -> parser.parse(input.getBytes(), false));
      assertTrue(e.issues.stream().anyMatch(i -> i.field().equals("date")));
    }
  }
}
