package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@com.example.payments.security.SecuredApi
@Path("/api/v1")
public class PaymentResource {
  @Inject MessageParser parser;
  @Inject CommandValidator validator;
  @Inject PaymentService service;
  @Inject SecurityIdentity identity;
  @Inject Representations representations;
  @Inject RequestContext context;
  @Context HttpHeaders headers;

  @POST
  @Path("/payments")
  public Response submit(String body) {
    representations.negotiate(headers);
    MediaType type = headers.getMediaType();
    boolean xml;
    if (type != null
        && type.getType().equalsIgnoreCase("application")
        && type.getSubtype().equalsIgnoreCase("json")) xml = false;
    else if (type != null
        && type.getType().equalsIgnoreCase("application")
        && type.getSubtype().equalsIgnoreCase("xml")) xml = true;
    else throw new PaymentException(415, "UNSUPPORTED_MEDIA_TYPE");
    var cmd = validator.validate(parser.parse(body, xml));
    context.paymentId = cmd.paymentId().toString();
    var outcome = service.transfer(cmd, identity.getPrincipal().getName(), context.correlationId);
    Response result = render(outcome, false);
    return Response.fromResponse(result)
        .header("Location", "/api/v1/payments/" + outcome.paymentId())
        .build();
  }

  @GET
  @Path("/payments/{id}")
  public Response find(@PathParam("id") String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new PaymentException(404, "PAYMENT_NOT_FOUND");
    }
    context.paymentId = uuid.toString();
    return render(service.find(uuid, identity.getPrincipal().getName()), true);
  }

  @GET
  @Path("/accounts/{id}/balance")
  public Response balance(@PathParam("id") String id) {
    return representations.response(
        200, service.balance(id, identity.getPrincipal().getName()), headers);
  }

  private Response render(PaymentService.Outcome o, boolean lookup) {
    context.status = o.status();
    context.code = o.rejectionCode() == null ? "NONE" : o.rejectionCode();
    var body = new LinkedHashMap<String, Object>();
    body.put("paymentId", o.paymentId());
    body.put("status", o.status());
    if (o.status().equals("REJECTED")) {
      body.put("code", o.rejectionCode());
      body.put("message", "The payment could not be completed.");
      body.put("issues", List.of());
      body.put("correlationId", o.correlationId());
      body.put("timestamp", o.processedAt().toString());
      return representations.response(lookup ? 200 : o.http(), body, headers);
    }
    body.put("fromAccount", o.fromAccount());
    body.put("toAccount", o.toAccount());
    body.put("amount", o.amount());
    body.put("currency", o.currency());
    body.put("processedAt", o.processedAt().toString());
    return representations.response(lookup || o.replay() ? 200 : 201, body, headers);
  }
}
