package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.Wire;
import com.example.payments.domain.ApiFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class NegotiationTest {
  @Test
  void honorsQualityAndExplicitExclusions() {
    assertEquals(
        MediaType.APPLICATION_XML, Wire.negotiate("application/json;q=0.4,application/xml;q=0.9"));
    assertEquals(MediaType.APPLICATION_XML, Wire.negotiate("application/json;q=0,*/*;q=1"));
    assertThrows(
        ApiFailure.class, () -> Wire.negotiate("application/json;q=0,application/xml;q=0"));
    assertEquals(MediaType.APPLICATION_JSON, Wire.negotiate("*/*"));
  }
}
