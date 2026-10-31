package com.example.payments.observability;

import com.example.payments.api.Wire;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class RequestAuditFilter extends OncePerRequestFilter {
  private final boolean tls, trust;
  private static final Logger LOG = LoggerFactory.getLogger("payment.audit");
  private final ObjectMapper json = new ObjectMapper();

  public RequestAuditFilter(
      @Value("${payment.require-tls:true}") boolean tls,
      @Value("${payment.trust-correlation-header:false}") boolean trust) {
    this.tls = tls;
    this.trust = trust;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String correlation = req.getHeader("X-Correlation-ID");
    if (!trust || correlation == null || !correlation.matches("[a-zA-Z0-9-]{1,128}"))
      correlation = UUID.randomUUID().toString();
    req.setAttribute("correlationId", correlation);
    res.setHeader("X-Correlation-ID", correlation);
    res.setHeader("Cache-Control", "no-store");
    long start = System.nanoTime();
    try {
      if (tls && req.getRequestURI().startsWith("/api/") && !req.isSecure()) {
        Wire.error(req, res, 403, "TLS_REQUIRED");
        return;
      }
      chain.doFilter(req, res);
    } finally {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("correlationId", correlation);
      entry.put("paymentId", req.getAttribute("paymentId"));
      entry.put("status", res.getStatus());
      entry.put("outcome", req.getAttribute("outcome"));
      entry.put("code", req.getAttribute("errorCode"));
      entry.put("latencyMs", (System.nanoTime() - start) / 1000000);
      LOG.info(json.writeValueAsString(entry));
    }
  }
}
