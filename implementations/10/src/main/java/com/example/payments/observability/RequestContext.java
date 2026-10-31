package com.example.payments.observability;

import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

@RequestScoped
public class RequestContext {
  private String correlation = UUID.randomUUID().toString();
  private boolean xml;
  private long start = System.nanoTime();

  public String correlation() {
    return correlation;
  }

  public void correlation(String value) {
    correlation = value;
  }

  public boolean xml() {
    return xml;
  }

  public void xml(boolean value) {
    xml = value;
  }

  public long start() {
    return start;
  }
}
