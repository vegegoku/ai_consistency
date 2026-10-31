package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiFailure;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/v1")
public class PaymentResource {
  @Inject PaymentParser parser;
  @Inject PaymentService service;
  @Inject Responses responses;
  @Inject JsonWebToken jwt;
  @Context HttpHeaders headers;
  @Context ContainerRequestContext request;

  private String correlation() {
    return (String) request.getProperty("correlation");
  }

  private String accept() {
    return headers.getHeaderString("Accept");
  }

  @POST
  @Path("/payments")
  @Consumes("*/*")
  @Produces({"application/json", "application/xml"})
  public Response submit(String body) {
    String type = headers.getHeaderString("Content-Type");
    if (type == null
        || !(type.split(";")[0].trim().equals("application/json")
            || type.split(";")[0].trim().equals("application/xml")))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    var command = parser.parse(body, type);
    var result = service.transfer(command, jwt.getSubject(), correlation());
    org.jboss.logging.Logger.getLogger(PaymentResource.class)
        .infov(
            "correlationId={0} paymentId={1} status={2} code={3}",
            correlation(),
            command.paymentId(),
            result.receipt().get("status"),
            result.receipt().getOrDefault("code", "NONE"));
    return Response.fromResponse(
            responses.send(result.http(), result.receipt(), accept(), correlation()))
        .header("Location", "/api/v1/payments/" + command.paymentId())
        .build();
  }

  @GET
  @Path("/payments/{id}")
  @Produces({"application/json", "application/xml"})
  public Response get(@PathParam("id") String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiFailure(404, "NOT_FOUND");
    }
    var result = service.get(uuid, jwt.getSubject());
    return responses.send(200, result.receipt(), accept(), correlation());
  }

  @GET
  @Path("/accounts/{id}/balance")
  @Produces({"application/json", "application/xml"})
  public Response balance(@PathParam("id") String id) {
    return responses.send(200, service.balance(id, jwt.getSubject()), accept(), correlation());
  }

  @GET
  @Path("/admin/reconciliation")
  @Produces({"application/json", "application/xml"})
  public Response reconcile() {
    return responses.send(200, service.reconcile(), accept(), correlation());
  }
}
