package com.example.payments.observability;

import com.example.payments.security.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;

@Provider
public class OutcomeFilter implements ContainerResponseFilter {
  @Inject RequestContext context;

  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    if (!request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/v1/")) return;
    response.getHeaders().putSingle("X-Correlation-ID", context.correlationId());
    Telemetry.event(
        context.correlationId(),
        context.paymentId(),
        context.status(),
        context.code(),
        System.nanoTime() - context.started(),
        context.replay(),
        request.getMethod().equals("POST"));
  }
}
