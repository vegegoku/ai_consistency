package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.PaymentException;
import org.junit.jupiter.api.Test;

class MessageParserTest {
  @Test
  void duplicateJsonCannotChangeMeaning() {
    assertThrows(
        PaymentException.class,
        () -> new MessageParser().parse("{\"amount\":1,\"amount\":2}", false));
  }

  @Test
  void xmlDtdIsForbidden() {
    assertThrows(
        PaymentException.class,
        () ->
            new MessageParser()
                .parse(
                    "<!DOCTYPE payment [<!ENTITY x SYSTEM"
                        + " 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>",
                    true));
  }
}
