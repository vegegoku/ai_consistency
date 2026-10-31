package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.mock.web.MockHttpServletRequest;

class ErrorContractTest {
  @Test
  void databaseUnavailableDiffersFromUnexpectedInvariantFailure() {
    var handler = new ErrorHandler(new Responses(), new SimpleMeterRegistry());
    var request = new MockHttpServletRequest();
    assertEquals(
        503,
        handler
            .handle(new DataAccessResourceFailureException("private database error"), request)
            .getStatusCode()
            .value());
    var response =
        handler.handle(new DataIntegrityViolationException("private SQL details"), request);
    assertEquals(500, response.getStatusCode().value());
    assertFalse(new String(response.getBody()).contains("private SQL"));
  }

  @Test
  void authenticationErrorsAdvertiseBearer() {
    var r = new Responses().response(401, java.util.Map.of("status", "ERROR"), false);
    assertEquals("Bearer", r.getHeaders().getFirst("WWW-Authenticate"));
  }
}
