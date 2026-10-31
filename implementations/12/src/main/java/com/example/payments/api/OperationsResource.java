package com.example.payments.api;

import com.example.payments.application.ReconciliationService;
import com.example.payments.security.*;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/v1/operations")
@Authenticated
public class OperationsResource {
  @Inject ReconciliationService reconciliation;
  @Inject JsonWebToken jwt;
  @Inject Responses responses;
  @Inject RateLimiter limiter;

  @GET
  @Path("/reconciliation")
  public Response report(@Context Request request) {
    var caller = Caller.from(jwt);
    caller.require("operations:read");
    limiter.check(caller);
    return responses.write(200, reconciliation.reconcile(), responses.negotiate(request));
  }
}
