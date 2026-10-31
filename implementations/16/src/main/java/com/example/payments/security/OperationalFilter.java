package com.example.payments.security;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class OperationalFilter extends OncePerRequestFilter {
  private record Window(long minute, int count) {}

  private final ConcurrentHashMap<String, Window> limits = new ConcurrentHashMap<>();
  private final ResponseWriter writer;
  private final boolean tls;
  private final int rate, max;

  OperationalFilter(ResponseWriter writer, boolean tls, int rate, int max) {
    this.writer = writer;
    this.tls = tls;
    this.rate = rate;
    this.max = max;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    if (q.getRequestURI().startsWith("/api/")) {
      if (tls && !q.isSecure()) {
        writer.writeError(q, s, new ApiFailure(403, "FORBIDDEN", "HTTPS is required."));
        return;
      }
      if (q.getContentLengthLong() > max) {
        writer.writeError(
            q, s, new ApiFailure(413, "PAYLOAD_TOO_LARGE", "Request exceeds the size limit."));
        return;
      }
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null) {
        long minute = System.currentTimeMillis() / 60000;
        if (limits.size() > 10000) limits.entrySet().removeIf(e -> e.getValue().minute() < minute);
        if (limits.size() > 10000 && !limits.containsKey(auth.getName())) {
          writer.writeError(
              q, s, new ApiFailure(429, "RATE_LIMITED", "Request rate limit exceeded."));
          return;
        }
        var w =
            limits.compute(
                auth.getName(),
                (k, old) ->
                    old == null || old.minute() != minute
                        ? new Window(minute, 1)
                        : new Window(minute, old.count() + 1));
        if (w.count() > rate) {
          writer.writeError(
              q, s, new ApiFailure(429, "RATE_LIMITED", "Request rate limit exceeded."));
          return;
        }
      }
    }
    chain.doFilter(q, s);
  }
}
