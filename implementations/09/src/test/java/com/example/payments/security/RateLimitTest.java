package com.example.payments.security;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.Responder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitTest {
  @Test
  void throttlesAuthenticatedPrincipalAndIncludesRetryAfter() throws Exception {
    var filter = new RateLimitFilter(new Responder(new ObjectMapper()), 2);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("alice", "", java.util.List.of()));
    try {
      for (int i = 0; i < 3; i++) {
        var request = new MockHttpServletRequest("POST", "/api/v1/payments");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
        if (i == 2) {
          assertThat(response.getHeader("Retry-After")).isEqualTo("2");
          assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        }
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
