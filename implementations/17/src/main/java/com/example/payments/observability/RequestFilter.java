package com.example.payments.observability;

import com.example.payments.api.ApiErrors;
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
  private final ApiErrors errors;
  private final boolean tls;

  public RequestFilter(ApiErrors errors, @Value("${payments.require-tls:true}") boolean tls) {
    this.errors = errors;
    this.tls = tls;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    r.setAttribute("correlationId", id);
    s.setHeader("X-Correlation-ID", id);
    MDC.put("correlationId", id);
    try {
      if (tls && !r.isSecure() && !r.getRequestURI().startsWith("/actuator/health")) {
        errors.write(r, s, 403, "FORBIDDEN");
        return;
      }
      chain.doFilter(r, s);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
