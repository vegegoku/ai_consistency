package com.example.payments.security;

import com.example.payments.domain.ApiException;
import java.time.Clock;
import java.util.*;

/** Fixed window per authenticated principal. Each replica enforces its own bounded limit. */
public final class RateLimiter {
  private record Window(long minute, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final int limit;
  private final Clock clock;

  public RateLimiter(int limit, Clock clock) {
    if (limit < 1) throw new IllegalArgumentException("Rate limit must be positive");
    this.limit = limit;
    this.clock = clock;
  }

  public synchronized void check(String principal) {
    long minute = clock.millis() / 60000;
    Window previous = windows.get(principal);
    if (previous != null && previous.minute() == minute) {
      if (previous.count() >= limit) throw new ApiException(429, "RATE_LIMITED");
      windows.put(principal, new Window(minute, previous.count() + 1));
    } else {
      if (windows.size() >= 10000) {
        windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        if (windows.size() >= 10000) throw new ApiException(429, "RATE_LIMITED");
      }
      windows.put(principal, new Window(minute, 1));
    }
  }
}
