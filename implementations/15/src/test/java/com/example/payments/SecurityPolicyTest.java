package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.PaymentException;
import com.example.payments.observability.BoundaryFilter;
import com.example.payments.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityPolicyTest {
  ResponseWriter writer = new ResponseWriter(new ObjectMapper());

  @Test
  void publicCorrelationHeaderCannotInjectLogsAndHttpsIsRequired() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/payments");
    request.addHeader("X-Correlation-ID", "untrusted");
    var response = new MockHttpServletResponse();
    new BoundaryFilter(writer, true, 65536).doFilter(request, response, new MockFilterChain());
    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("HTTPS_REQUIRED"));
    assertNotEquals("untrusted", response.getHeader("X-Correlation-ID"));
  }

  @Test
  void requestSizeLimitStopsBeforeApplication() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/payments");
    request.setContent(new byte[65537]);
    var response = new MockHttpServletResponse();
    new BoundaryFilter(writer, false, 65536).doFilter(request, response, new MockFilterChain());
    assertEquals(413, response.getStatus());
  }

  @Test
  void authenticatedPrincipalCannotExceedMinuteLimit() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "alice", "ignored", java.util.List.of()));
    try {
      var filter = new PrincipalRateLimitFilter(2, writer);
      AtomicInteger passed = new AtomicInteger();
      for (int i = 0; i < 3; i++) {
        var request = new MockHttpServletRequest("GET", "/api/v1/payments/a");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (q, s) -> passed.incrementAndGet());
        if (i == 2) {
          assertEquals(429, response.getStatus());
          assertEquals("2", response.getHeader("Retry-After"));
        }
      }
      assertEquals(2, passed.get());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void negotiationHonorsQualityAndExplicitExclusions() {
    assertEquals(MediaType.APPLICATION_XML, writer.negotiate("application/json;q=0, */*;q=0.8"));
    assertEquals(
        MediaType.APPLICATION_JSON, writer.negotiate("application/xml;q=0.5,application/json;q=1"));
    assertThrows(PaymentException.class, () -> writer.negotiate("image/png"));
    assertThrows(
        PaymentException.class, () -> writer.negotiate("application/json;q=0,application/xml;q=0"));
  }
}
