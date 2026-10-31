package com.example.payments;

import static org.assertj.core.api.Assertions.*;

import com.example.payments.api.Responder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

class NegotiationTest {
  @Test
  void respectsExplicitJsonExclusionOverWildcard() {
    var request = new MockHttpServletRequest();
    request.addHeader("Accept", "application/json;q=0, */*;q=1");
    assertThat(
            new Responder(new ObjectMapper())
                .response(200, java.util.Map.of("status", "COMPLETED"), request)
                .getHeaders()
                .getContentType())
        .isEqualTo(MediaType.APPLICATION_XML);
  }
}
