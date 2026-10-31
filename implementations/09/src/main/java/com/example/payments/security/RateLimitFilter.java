package com.example.payments.security;

import com.example.payments.api.Responder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded-minute per-principal local fallback; use shared ingress quotas across replicas. */
public final class RateLimitFilter extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final Responder responder;
  private final int limit;

  public RateLimitFilter(Responder responder, int limit) {
    this.responder = responder;
    this.limit = limit;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && request.getRequestURI().startsWith("/api/")) {
      long minute = System.currentTimeMillis() / 60000;
      if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
      Window window =
          windows.compute(
              auth.getName(),
              (key, prior) ->
                  new Window(
                      minute, prior == null || prior.minute != minute ? 1 : prior.count + 1));
      if (window.count > limit) {
        responder.error(request, response, 429, "RATE_LIMITED");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
