package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.api.Responses;
import com.example.payments.domain.ApiException;
import com.example.payments.observability.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RequestProtectionTest {
  @Test
  void tlsIsRequiredByDefaultAndCorrelationIsGenerated() throws Exception {
    var f = new RequestFilter(new Responses(), true, false, 65536);
    var request = new MockHttpServletRequest("POST", "/api/v1/payments");
    request.addHeader("X-Correlation-ID", "untrusted");
    var response = new MockHttpServletResponse();
    f.doFilter(request, response, new MockFilterChain());
    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("TLS_REQUIRED"));
    assertDoesNotThrow(() -> java.util.UUID.fromString(response.getHeader("X-Correlation-ID")));
  }

  @Test
  void rateLimitHasBoundedWindow() {
    var limiter = new RateLimitInterceptor(1);
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    assertTrue(limiter.preHandle(request, response, new Object()));
    assertEquals(
        "RATE_LIMITED",
        assertThrows(ApiException.class, () -> limiter.preHandle(request, response, new Object()))
            .code);
  }

  @Test
  void secureRequestPassesAndTrustedCorrelationIsValidated() throws Exception {
    var f = new RequestFilter(new Responses(), true, true, 65536);
    var request = new MockHttpServletRequest();
    request.setSecure(true);
    String id = java.util.UUID.randomUUID().toString();
    request.addHeader("X-Correlation-ID", id);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    f.doFilter(request, response, chain);
    assertNotNull(chain.getRequest());
    assertEquals(id, response.getHeader("X-Correlation-ID"));
    assertNull(org.slf4j.MDC.get("correlationId"));
  }
}
