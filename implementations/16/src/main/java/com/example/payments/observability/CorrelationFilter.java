package com.example.payments.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(CorrelationFilter.class);
  private final boolean trust;

  public CorrelationFilter(@Value("${payments.trust-correlation-header:false}") boolean trust) {
    this.trust = trust;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    String supplied = q.getHeader("X-Correlation-ID");
    String cid =
        trust && supplied != null && supplied.matches("[A-Za-z0-9-]{1,100}")
            ? supplied
            : UUID.randomUUID().toString();
    q.setAttribute("correlationId", cid);
    s.setHeader("X-Correlation-ID", cid);
    MDC.put("correlationId", cid);
    long start = System.nanoTime();
    try {
      chain.doFilter(q, s);
    } finally {
      Object id = q.getAttribute("paymentId");
      LOG.atInfo()
          .addKeyValue("correlationId", cid)
          .addKeyValue("paymentId", Objects.toString(id, ""))
          .addKeyValue("status", s.getStatus())
          .addKeyValue("errorCode", Objects.toString(q.getAttribute("errorCode"), ""))
          .addKeyValue("principal", Objects.toString(q.getAttribute("principal"), ""))
          .addKeyValue("latencyMs", (System.nanoTime() - start) / 1_000_000)
          .log("request_processed");
      MDC.clear();
    }
  }
}
