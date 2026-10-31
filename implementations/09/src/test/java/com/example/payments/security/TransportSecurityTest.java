package com.example.payments.security;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.Responder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class TransportSecurityTest {
  @Test
  void rejectsPlaintextApiBeforeProcessing() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/payments");
    var response = new MockHttpServletResponse();
    new RequestFilter(true, new Responder(new ObjectMapper()))
        .doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("TLS_REQUIRED");
    assertThat(response.getHeader("X-Correlation-ID")).isNotBlank();
  }
}
