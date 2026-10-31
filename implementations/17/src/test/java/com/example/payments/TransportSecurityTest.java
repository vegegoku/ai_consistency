package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.ApiErrors;
import com.example.payments.observability.RequestFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class TransportSecurityTest {
  @Test
  void deployedEndpointsRequireActualTlsAndIgnoreUntrustedCorrelation() throws Exception {
    var filter = new RequestFilter(new ApiErrors(new SimpleMeterRegistry()), true);
    var request = new MockHttpServletRequest("GET", "/api/v1/accounts/A/balance");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Correlation-ID", "attacker-controlled");
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (r, s) -> {
          throw new AssertionError("Insecure request passed");
        });
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("FORBIDDEN");
    assertThat(response.getHeader("X-Correlation-ID")).isNotEqualTo("attacker-controlled");
    request = new MockHttpServletRequest("GET", "/api/v1/accounts/A/balance");
    request.setSecure(true);
    response = new MockHttpServletResponse();
    filter.doFilter(request, response, (r, s) -> s.getWriter().write("secure"));
    assertThat(response.getContentAsString()).isEqualTo("secure");
  }
}
