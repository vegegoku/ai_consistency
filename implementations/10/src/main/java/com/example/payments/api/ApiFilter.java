package com.example.payments.api;

import com.example.payments.application.Settings;
import com.example.payments.domain.Problem;
import com.example.payments.observability.RequestContext;
import com.example.payments.security.Caller;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;

@Provider
public class ApiFilter implements ContainerRequestFilter, ContainerResponseFilter {
  @Inject io.micrometer.core.instrument.MeterRegistry metrics;
  @Inject Caller caller;
  @Inject com.example.payments.application.PaymentService service;
  @Inject Settings settings;
  @Inject RequestContext context;

  public void filter(ContainerRequestContext r) {
    if (!r.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/")) return;
    String correlation = r.getHeaderString("X-Correlation-ID");
    if (correlation != null)
      try {
        context.correlation(UUID.fromString(correlation).toString());
      } catch (Exception ignored) {
      }
    var accept = r.getAcceptableMediaTypes();
    boolean supported = false;
    for (var a : accept) {
      if (new java.math.BigDecimal(a.getParameters().getOrDefault("q", "1")).signum() == 0)
        continue;
      if (a.isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)) {
        supported = true;
        context.xml(false);
        break;
      }
      if (a.isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_XML_TYPE)) {
        supported = true;
        context.xml(true);
        break;
      }
    }
    if (!supported) throw new Problem(406, "NOT_ACCEPTABLE");
    if (settings.tls && !r.getSecurityContext().isSecure()) throw new Problem(403, "FORBIDDEN");
    caller.authenticate(r.getHeaderString("Authorization"));
    service.checkRate(caller.principal());
  }

  public void filter(ContainerRequestContext r, ContainerResponseContext s) {
    if (r.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/")) {
      metrics.counter("payments.requests", "status", String.valueOf(s.getStatus())).increment();
      s.getHeaders().putSingle("X-Correlation-ID", context.correlation());
      s.getHeaders().putSingle("Cache-Control", "no-store");
      s.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
    }
  }
}
