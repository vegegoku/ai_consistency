package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.Problem;
import com.example.payments.observability.RequestContext;
import com.example.payments.security.Caller;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.util.*;

@Path("/api/v1")
public class PaymentResource {
  @Inject PaymentService service;
  @Inject MessageCodec codec;
  @Inject Caller caller;
  @Inject RequestContext context;

  @POST
  @Path("/payments")
  @Consumes({"application/json", "application/xml"})
  @Produces({"application/json", "application/xml"})
  public Response submit(byte[] body, @HeaderParam("Content-Type") String type) {
    caller.scope("payments:write");
    String media = MediaType.valueOf(type).getType() + "/" + MediaType.valueOf(type).getSubtype();
    if (!Set.of("application/json", "application/xml").contains(media))
      throw new Problem(415, "UNSUPPORTED_MEDIA_TYPE");
    var cmd = codec.decode(body, media, id -> service.isRecorded(id, caller.principal()));
    var result = service.transfer(cmd, caller.principal(), context.correlation());
    var r = response(result.http(), result.body());
    if (result.http() == 201) r.location(URI.create("/api/v1/payments/" + cmd.paymentId()));
    return r.build();
  }

  @GET
  @Path("/payments/{id}")
  @Produces({"application/json", "application/xml"})
  public Response get(@PathParam("id") String id) {
    caller.scope("payments:read");
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new Problem(404, "PAYMENT_NOT_FOUND");
    }
    var result = service.get(uuid, caller.principal());
    return response(
            result.http() == 409 || result.http() == 403 ? 200 : result.http(), result.body())
        .build();
  }

  @GET
  @Path("/accounts/{id}/balance")
  @Produces({"application/json", "application/xml"})
  public Response balance(@PathParam("id") String id) {
    caller.scope("accounts:read");
    return response(200, service.balance(id, caller.principal())).build();
  }

  @GET
  @Path("/admin/reconciliation")
  @Produces({"application/json", "application/xml"})
  public Response reconciliation() {
    caller.scope("payments:admin");
    return response(200, Map.of("issues", service.reconcile())).build();
  }

  private Response.ResponseBuilder response(int status, Object body) {
    return Response.status(status)
        .type(context.xml() ? "application/xml" : "application/json")
        .entity(codec.encode(body, context.xml()));
  }
}
