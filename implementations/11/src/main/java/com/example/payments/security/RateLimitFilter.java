package com.example.payments.security;

import com.example.payments.api.Wire;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class RateLimitFilter extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final int max;
  private final Map<String, Window> windows =
      new LinkedHashMap<>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Window> e) {
          return size() > 10000;
        }
      };

  RateLimitFilter(int max) {
    this.max = max;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && req.getRequestURI().startsWith("/api/")) {
      long minute = System.currentTimeMillis() / 60000;
      boolean allowed;
      synchronized (windows) {
        var previous = windows.get(auth.getName());
        int count = previous != null && previous.minute == minute ? previous.count + 1 : 1;
        windows.put(auth.getName(), new Window(minute, count));
        allowed = count <= max;
      }
      if (!allowed) {
        Wire.error(req, res, 429, "RATE_LIMITED");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
