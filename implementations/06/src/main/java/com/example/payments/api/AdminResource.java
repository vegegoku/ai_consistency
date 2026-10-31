package com.example.payments.api;

import com.example.payments.application.ReconciliationService;
import com.example.payments.domain.PaymentException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@com.example.payments.security.SecuredApi
@Path("/api/v1/admin/reconciliation")
public class AdminResource {
  @Inject SecurityIdentity identity;
  @Inject ReconciliationService reconciliation;
  @Inject Representations representations;
  @Context HttpHeaders headers;

  @GET
  public Response report() {
    if (!identity.hasRole("admin")) throw new PaymentException(403, "FORBIDDEN");
    return representations.response(200, reconciliation.report(), headers);
  }
}
