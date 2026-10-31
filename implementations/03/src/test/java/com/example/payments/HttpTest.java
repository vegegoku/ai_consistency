package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.Responses;
import com.example.payments.domain.ApiFailure;
import org.junit.jupiter.api.Test;

class HttpTest {
  @Test
  void rejectsUnknownResponseFormat() {
    assertEquals(
        406, assertThrows(ApiFailure.class, () -> new Responses().media("text/html")).http);
  }

  @Test
  void qualityNegotiation() {
    var r = new Responses();
    assertEquals("application/xml", r.media("application/json;q=0.1, application/xml;q=0.9"));
    assertEquals(406, assertThrows(ApiFailure.class, () -> r.media("text/html, */*;q=0")).http);
  }

  @Test
  void responseFormats() {
    var r = new Responses();
    assertEquals("application/xml", r.media("application/xml"));
    assertEquals("application/json", r.media("*/*"));
  }
}
