package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.PaymentException;
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
  void decimalIsExactAndPurposeTrimmed() {
    var c = parser.parse(json("125.750").getBytes(), "application/json");
    assertThat(c.amount().toPlainString()).isEqualTo("125.750");
    assertThat(c.purpose()).isEqualTo("invoice");
  }

  @Test
  void rejectsExcessScaleWithoutRounding() {
    assertThatThrownBy(() -> parser.parse(json("1.0000").getBytes(), "application/json"))
        .isInstanceOf(PaymentException.class)
        .hasMessage("VALIDATION_FAILED");
  }

  @Test
  void rejectsDuplicateJsonAndWrongTypes() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2").getBytes(),
                    "application/json"))
        .hasMessage("MALFORMED_MESSAGE");
    assertThatThrownBy(() -> parser.parse(json("true").getBytes(), "application/json"))
        .hasMessage("MALFORMED_MESSAGE");
  }

  @Test
  void xmlRejectsDtdDuplicateAndUnknown() {
    for (String xml :
        new String[] {
          "<!DOCTYPE payment [<!ENTITY x SYSTEM"
              + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
          "<payment><amount>1</amount><amount>2</amount></payment>",
          "<payment><unknown>1</unknown></payment>",
          "<payment><purpose><nested/></purpose></payment>"
        })
      assertThatThrownBy(() -> parser.parse(xml.getBytes(), "application/xml"))
          .hasMessage("MALFORMED_MESSAGE");
  }

  @Test
  void collectsFieldIssues() {
    try {
      parser.parse("{}".getBytes(), "application/json");
      fail("expected validation");
    } catch (PaymentException e) {
      assertThat(e.issues).hasSize(6);
    }
  }

  @Test
  void exactExponentNotationUsesBigDecimal() {
    assertThat(parser.parse(json("1.25750e2").getBytes(), "application/json").amount())
        .isEqualByComparingTo("125.750");
  }

  @Test
  void identifiersArePreservedVerbatim() {
    assertThat(
            parser
                .parse(
                    json("1")
                        .replace("\"fromAccount\":\"A\"", "\"fromAccount\":\"account A\"")
                        .getBytes(),
                    "application/json")
                .fromAccount())
        .isEqualTo("account A");
  }
}
