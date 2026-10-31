package com.example.payments.observability;

import com.example.payments.api.Responses;
import com.example.payments.domain.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class RequestFilter extends OncePerRequestFilter {
  private final Responses responses;
  private final boolean tls, trusted;
  private final long maxBody;

  public RequestFilter(
      Responses r,
      @Value("${payments.require-tls:true}") boolean tls,
      @Value("${payments.trust-correlation-header:false}") boolean trusted,
      @Value("${payments.max-body:65536}") long maxBody) {
    responses = r;
    this.tls = tls;
    this.trusted = trusted;
    this.maxBody = maxBody;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    String id = UUID.randomUUID().toString();
    if (trusted)
      try {
        id = UUID.fromString(r.getHeader("X-Correlation-ID")).toString();
      } catch (Exception ignored) {
      }
    MDC.put("correlationId", id);
    s.setHeader("X-Correlation-ID", id);
    s.setHeader("X-Content-Type-Options", "nosniff");
    try {
      if (tls && !r.isSecure()) {
        responses.write(r, s, new ApiException(403, "TLS_REQUIRED"));
        return;
      }
      if (r.getContentLengthLong() > maxBody) {
        responses.write(r, s, new ApiException(413, "MESSAGE_TOO_LARGE"));
        return;
      }
      chain.doFilter(r, s);
    } finally {
      org.slf4j.LoggerFactory.getLogger(RequestFilter.class)
          .atInfo()
          .addKeyValue("httpStatus", s.getStatus())
          .addKeyValue("latencyMs", (System.nanoTime() - started) / 1_000_000)
          .log("request completed");
      MDC.remove("correlationId");
    }
  }
}
