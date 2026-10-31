package com.example.payments.security;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.PaymentException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class PrincipalRateLimitFilter extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final int max;
  private final ResponseWriter writer;

  public PrincipalRateLimitFilter(int max, ResponseWriter writer) {
    this.max = max;
    this.writer = writer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && request.getRequestURI().startsWith("/api/")) {
      long minute = System.currentTimeMillis() / 60000;
      if (windows.size() > 10000) {
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        if (windows.size() > 10000 && !windows.containsKey(auth.getName())) {
          writer.error(
              request,
              response,
              new PaymentException(429, "RATE_LIMITED", "Request limit exceeded."));
          return;
        }
      }
      Window current =
          windows.compute(
              auth.getName(),
              (key, old) ->
                  old == null || old.minute() != minute
                      ? new Window(minute, 1)
                      : new Window(minute, Math.min(old.count() + 1, max + 1)));
      if (current.count() > max) {
        writer.error(
            request,
            response,
            new PaymentException(429, "RATE_LIMITED", "Request limit exceeded."));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
