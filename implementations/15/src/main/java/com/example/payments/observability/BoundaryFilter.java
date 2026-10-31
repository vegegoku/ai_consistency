package com.example.payments.observability;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.PaymentException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class BoundaryFilter extends OncePerRequestFilter {
  private final ResponseWriter writer;
  private final boolean https;
  private final int maxBytes;

  public BoundaryFilter(ResponseWriter writer, boolean https, int maxBytes) {
    this.writer = writer;
    this.https = https;
    this.maxBytes = maxBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute("requestStartedNanos", System.nanoTime());
    request.setAttribute("correlationId", id);
    response.setHeader("X-Correlation-ID", id);
    MDC.put("correlationId", id);
    try {
      if (request.getRequestURI().startsWith("/api/") && https && !request.isSecure()) {
        writer.error(
            request, response, new PaymentException(400, "HTTPS_REQUIRED", "HTTPS is required."));
        return;
      }
      if (request.getContentLengthLong() > maxBytes) {
        writer.error(
            request,
            response,
            new PaymentException(
                413, "PAYLOAD_TOO_LARGE", "Request exceeds the configured limit."));
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
