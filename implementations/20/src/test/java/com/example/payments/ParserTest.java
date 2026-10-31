package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.PaymentParser;
import org.junit.jupiter.api.Test;

class ParserTest {
  @Test
  void duplicateJsonRejected() {
    assertThrows(
        RuntimeException.class,
        () -> new PaymentParser().parse("{\"amount\":1,\"amount\":2}", false));
  }

  @Test
  void xxeRejected() {
    assertThrows(
        RuntimeException.class,
        () ->
            new PaymentParser()
                .parse(
                    "<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment><notes>&x;</notes></payment>",
                    true));
  }

  @Test
  void duplicatesXmlRejected() {
    assertThrows(
        RuntimeException.class,
        () ->
            new PaymentParser()
                .parse("<payment><amount>1</amount><amount>2</amount></payment>", true));
  }

  @Test
  void nestedXmlRejected() {
    assertThrows(
        RuntimeException.class,
        () -> new PaymentParser().parse("<payment><notes><nested/></notes></payment>", true));
  }
}
