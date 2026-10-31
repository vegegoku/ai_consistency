package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  final MessageParser parser = new MessageParser();

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
  void decimalIsExactAndPurposeTrimmed() {
    var c = parser.parse(json("125.750").getBytes(), false);
    assertEquals("125.750", c.amount().toPlainString());
    assertEquals("invoice", c.purpose());
  }

  @Test
  void rejectsDuplicateUnknownAndWrongTypes() {
    for (String s :
        new String[] {
          json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),
          json("1").replace("\"purpose\"", "\"unknown\""),
          json("true"),
          json("[]"),
          json("1") + " {}"
        }) assertThrows(ApiException.class, () -> parser.parse(s.getBytes(), false));
  }

  @Test
  void accumulatesFieldFailures() {
    var ex = assertThrows(ApiException.class, () -> parser.parse("{}".getBytes(), false));
    assertTrue(ex.issues().size() >= 6);
  }

  @Test
  void rejectsUnsafeMoney() {
    for (String a : new String[] {"0", "-1", "1.0001", "10000.001"})
      assertThrows(ApiException.class, () -> parser.parse(json(a).getBytes(), false));
  }

  @Test
  void xmlSecurityAndDuplicates() {
    for (String x :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment><purpose><nested/></purpose></payment>",
          "<wrong/>"
        }) assertThrows(ApiException.class, () -> parser.parse(x.getBytes(), true));
  }

  @Test
  void jsonAndXmlHaveSameCanonicalFingerprint() {
    String id = UUID.randomUUID().toString();
    String date = Instant.now().toString();
    String a =
        "{\"paymentId\":\""
            + id
            + "\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":1.000,\"date\":\""
            + date
            + "\",\"purpose\":\" test \"}";
    String b =
        "<payment><paymentId>"
            + id
            + "</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>1</amount><date>"
            + date
            + "</date><purpose>test</purpose><notes></notes></payment>";
    assertEquals(
        parser.parse(a.getBytes(), false).fingerprint(),
        parser.parse(b.getBytes(), true).fingerprint());
  }
}
