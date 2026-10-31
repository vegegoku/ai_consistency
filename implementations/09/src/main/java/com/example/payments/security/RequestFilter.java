package com.example.payments.security;

import com.example.payments.api.Responder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
  private final boolean tls;
  private final Responder responder;

  public RequestFilter(@Value("${payments.require-tls}") boolean tls, Responder responder) {
    this.tls = tls;
    this.responder = responder;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String correlation = UUID.randomUUID().toString();
    req.setAttribute("correlationId", correlation);
    res.setHeader("X-Correlation-ID", correlation);
    MDC.put("correlationId", correlation);
    try {
      if (tls && req.getRequestURI().startsWith("/api/") && !req.isSecure()) {
        responder.error(req, res, 403, "TLS_REQUIRED");
        return;
      }
      chain.doFilter(req, res);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
