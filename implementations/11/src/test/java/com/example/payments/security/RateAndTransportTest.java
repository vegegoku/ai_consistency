package com.example.payments.security;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.observability.RequestAuditFilter;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateAndTransportTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void boundedRateReturnsRetryAfter() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "", List.of()));
    var filter = new RateLimitFilter(2);
    for (int i = 0; i < 3; i++) {
      var request = new MockHttpServletRequest("POST", "/api/v1/payments");
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (a, b) -> {});
      if (i == 2) {
        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("RATE_LIMITED"));
      }
    }
  }

  @Test
  void requiresTlsAndIgnoresUntrustedCorrelation() throws Exception {
    var req = new MockHttpServletRequest("POST", "/api/v1/payments");
    req.addHeader("X-Correlation-ID", "client-chosen");
    var res = new MockHttpServletResponse();
    new RequestAuditFilter(true, false)
        .doFilter(req, res, (a, b) -> fail("Insecure transport accepted"));
    assertEquals(403, res.getStatus());
    assertNotEquals("client-chosen", res.getHeader("X-Correlation-ID"));
    assertTrue(res.getContentAsString().contains("TLS_REQUIRED"));
  }
}
