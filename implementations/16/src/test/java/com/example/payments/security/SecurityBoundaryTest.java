package com.example.payments.security;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.ResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityBoundaryTest {
  final ResponseWriter writer = new ResponseWriter(new ObjectMapper());

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  MockHttpServletRequest request() {
    var r = new MockHttpServletRequest("POST", "/api/v1/payments");
    r.setSecure(true);
    return r;
  }

  @Test
  void enforcesTlsWithoutTrustingForwardedHeader() throws Exception {
    var r = request();
    r.setSecure(false);
    r.addHeader("X-Forwarded-Proto", "https");
    var s = new MockHttpServletResponse();
    new OperationalFilter(writer, true, 100, 65536)
        .doFilter(r, s, (q, p) -> fail("Transport bypass"));
    assertEquals(403, s.getStatus());
  }

  @Test
  void enforcesDeclaredBodyLimit() throws Exception {
    var r = request();
    r.setContent(new byte[100]);
    var s = new MockHttpServletResponse();
    new OperationalFilter(writer, false, 100, 64)
        .doFilter(r, s, (q, p) -> fail("Body limit bypass"));
    assertEquals(413, s.getStatus());
  }

  @Test
  void rateLimitReturnsRetryAfter() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused", List.of()));
    var filter = new OperationalFilter(writer, false, 2, 65536);
    for (int i = 0; i < 2; i++) {
      var reached = new AtomicBoolean();
      filter.doFilter(request(), new MockHttpServletResponse(), (q, p) -> reached.set(true));
      assertTrue(reached.get());
    }
    var response = new MockHttpServletResponse();
    filter.doFilter(request(), response, (q, p) -> fail("Rate limit bypass"));
    assertEquals(429, response.getStatus());
    assertEquals("2", response.getHeader("Retry-After"));
  }
}
