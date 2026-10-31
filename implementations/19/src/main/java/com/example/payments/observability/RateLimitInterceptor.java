package com.example.payments.observability;

import com.example.payments.domain.ApiException;
import jakarta.servlet.http.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Component
public class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {
  private record Window(long second, int count) {}

  private final ConcurrentHashMap<String, Window> counts = new ConcurrentHashMap<>();
  private final int limit;

  public RateLimitInterceptor(@Value("${payments.rate-per-second:150}") int limit) {
    this.limit = limit;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/**");
  }

  @Override
  public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object h) {
    long second = System.currentTimeMillis() / 1000;
    String key = r.getUserPrincipal() == null ? r.getRemoteAddr() : r.getUserPrincipal().getName();
    Window w =
        counts.compute(
            key,
            (k, old) ->
                old == null || old.second != second
                    ? new Window(second, 1)
                    : new Window(second, old.count + 1));
    if (counts.size() > 10000) counts.entrySet().removeIf(e -> e.getValue().second < second - 60);
    if (w.count > limit) throw new ApiException(429, "RATE_LIMITED");
    return true;
  }
}
