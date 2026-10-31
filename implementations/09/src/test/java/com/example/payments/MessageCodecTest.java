package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.MessageCodec;
import com.example.payments.domain.ApiError;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageCodecTest {
  MessageCodec codec = new MessageCodec(new BigDecimal("10000.000"), 2592000, 300, 65536);
  String valid =
      "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":1.234,\"date\":\""
          + Instant.now()
          + "\",\"purpose\":\" hello \"}";

  void bad(String body, String type, String code) {
    assertThatThrownBy(() -> codec.read(new ByteArrayInputStream(body.getBytes()), type))
        .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code).isEqualTo(code));
  }

  @Test
  void rejectsDuplicateUnknownAndWrongTypedFields() {
    bad(
        valid.replace("\"amount\":1.234", "\"amount\":1,\"amount\":2"),
        "application/json",
        "MALFORMED_MESSAGE");
    bad(
        valid.replace("\"amount\":1.234", "\"extra\":1,\"amount\":2"),
        "application/json",
        "MALFORMED_MESSAGE");
    bad(
        valid.replace("\"fromAccount\":\"A\"", "\"fromAccount\":2"),
        "application/json",
        "MALFORMED_MESSAGE");
    bad(valid + " {}", "application/json", "MALFORMED_MESSAGE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1.2345", "10000.001", "\"NaN\"", "true", "1.2300"})
  void rejectsInvalidMoney(String amount) {
    bad(
        valid.replace("1.234", amount),
        "application/json",
        amount.equals("true") ? "MALFORMED_MESSAGE" : "VALIDATION_FAILED");
  }

  @Test
  void preservesExactDecimalAndTrimsDescriptiveText() {
    var c = codec.read(new ByteArrayInputStream(valid.getBytes()), "application/json");
    assertThat(c.amount().toPlainString()).isEqualTo("1.234");
    assertThat(c.purpose()).isEqualTo("hello");
  }

  @Test
  void gathersSafeFieldIssues() {
    assertThatThrownBy(
            () -> codec.read(new ByteArrayInputStream("{}".getBytes()), "application/json"))
        .isInstanceOfSatisfying(
            ApiError.class, e -> assertThat(e.issues.size()).isGreaterThanOrEqualTo(6));
  }

  @Test
  void rejectsDateWithoutOffsetAndOutOfWindow() {
    for (String d :
        java.util.List.of(
            "2026-10-28T13:00:00",
            Instant.now().plusSeconds(301).toString(),
            Instant.now().minusSeconds(2592100).toString()))
      bad(
          valid.replaceAll("\\\"date\\\":\\\"[^\\\"]+\\\"", "\"date\":\"" + d + "\""),
          "application/json",
          "VALIDATION_FAILED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<!DOCTYPE payment [<!ENTITY e SYSTEM"
            + " 'file:///etc/passwd'>]><payment><purpose>&e;</purpose></payment>",
        "<!DOCTYPE payment SYSTEM 'http://127.0.0.1:1/evil'><payment/>",
        "<payment><amount>1</amount><amount>2</amount></payment>",
        "<payment><unknown>1</unknown></payment>",
        "<payment><purpose><nested/></purpose></payment>",
        "<payment xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include"
            + " href='file:///etc/passwd'/></payment>",
        "<notPayment/>",
        "<payment"
      })
  void rejectsHostileXml(String s) {
    bad(s, "application/xml", "MALFORMED_MESSAGE");
  }

  @Test
  void bodyLimitAndExcessiveJsonDepth() {
    assertThatThrownBy(
            () -> codec.read(new ByteArrayInputStream(new byte[65537]), "application/json"))
        .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.http).isEqualTo(413));
    bad(
        "{\"purpose\":" + "[".repeat(12) + "0" + "]".repeat(12) + "}",
        "application/json",
        "MALFORMED_MESSAGE");
  }
}
