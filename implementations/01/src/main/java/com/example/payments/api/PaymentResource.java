package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
@Produces("*/*")
public class PaymentResource {
  @Inject MessageParser parser;
  @Inject PaymentService service;
  @Inject SecurityIdentity identity;
  @Inject RequestContext context;

  @ConfigProperty(name = "payment.max-body", defaultValue = "65536")
  int maximumBody;

  @ConfigProperty(name = "payment.rate-per-minute", defaultValue = "6000")
  int rateLimit;

  @Context HttpHeaders headers;

  private Caller caller() {
    return new Caller(identity.getPrincipal().getName(), identity.hasRole("payments:admin"));
  }

  private Caller limited() {
    var caller = caller();
    service.rateLimit(caller, rateLimit);
    return caller;
  }

  @POST
  @Path("/payments")
  @Consumes("*/*")
  @RolesAllowed({"payments:write", "payments:admin"})
  public Response submit(InputStream body) throws IOException {
    String media = ResponseCodec.negotiate(headers);
    var caller = limited();
    String content = headers.getHeaderString("Content-Type");
    String base = content == null ? "" : content.split(";")[0].trim();
    if (!Set.of("application/json", "application/xml").contains(base.toLowerCase(Locale.ROOT)))
      throw new PaymentException(415, "UNSUPPORTED_MEDIA_TYPE");
    var command = parser.parse(body.readNBytes(maximumBody + 1), content);
    context.paymentId(command.paymentId().toString());
    var result = service.transfer(command, caller, context.correlation());
    context.code((String) result.body().getOrDefault("code", "NONE"));
    var response = ResponseCodec.response(result.http(), result.body(), media);
    if (result.http() == 201)
      response.location(URI.create("/api/v1/payments/" + command.paymentId()));
    return response.build();
  }

  @GET
  @Path("/payments/{paymentId}")
  @RolesAllowed({"payments:read", "payments:admin"})
  public Response find(@PathParam("paymentId") String id) {
    String media = ResponseCodec.negotiate(headers);
    var caller = limited();
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
      if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new PaymentException(404, "PAYMENT_NOT_FOUND");
    }
    context.paymentId(uuid.toString());
    return ResponseCodec.response(200, service.find(uuid, caller).body(), media).build();
  }

  @GET
  @Path("/accounts/{accountId}/balance")
  @RolesAllowed({"payments:read", "payments:admin"})
  public Response balance(@PathParam("accountId") String id) {
    String media = ResponseCodec.negotiate(headers);
    return ResponseCodec.response(200, service.balance(id, limited()), media).build();
  }

  @GET
  @Path("/admin/reconciliation")
  @RolesAllowed("payments:admin")
  public Response reconcile() {
    String media = ResponseCodec.negotiate(headers);
    limited();
    var findings = service.reconcile();
    return ResponseCodec.response(
            200,
            Map.of("status", findings.isEmpty() ? "BALANCED" : "MISMATCH", "findings", findings),
            media)
        .build();
  }
}
