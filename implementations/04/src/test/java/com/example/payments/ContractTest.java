package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.PaymentParser;
import com.example.payments.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ContractTest {
  final PaymentParser parser = new PaymentParser();
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
  void canonicalScaleAndWhitespace() {
    var c = parser.parse(json("125.750").getBytes(), false);
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void invalidPrecisionAndMissingFields() {
    assertThrows(ApiException.class, () -> parser.parse(json("0.0001").getBytes(), false));
    var e = assertThrows(ApiException.class, () -> parser.parse("{}".getBytes(), false));
    assertTrue(e.issues.size() >= 6);
  }

  @Test
  void rejectsDuplicateUnknownAndTypedValues() {
    for (String s :
        new String[] {
          json("true"),
          json("1").replace("\"purpose\":", "\"unexpected\":0,\"purpose\":"),
          json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2")
        }) assertThrows(ApiException.class, () -> parser.parse(s.getBytes(), false));
  }

  @Test
  void xmlRejectsDoctypeDuplicateAndAttributes() {
    for (String s :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment>&x;</payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment a='1'/>"
        }) assertThrows(ApiException.class, () -> parser.parse(s.getBytes(), true));
  }

  @Test
  void samePayloadBothFormats() {
    String date = Instant.now().toString();
    String j = json("1").replaceAll("\\\"date\\\":\\\"[^\\\"]+", "\"date\":\"" + date);
    String x =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>1.000</amount><date>"
            + date
            + "</date><purpose>invoice</purpose></payment>";
    assertEquals(
        parser.parse(j.getBytes(), false).fingerprint(),
        parser.parse(x.getBytes(), true).fingerprint());
  }
}
