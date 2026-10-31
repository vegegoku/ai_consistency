package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.security.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

@io.smallrye.common.annotation.Blocking
@Path("/api/v1")
public class PaymentResource {
  @Inject PaymentService service;
  @Inject MessageCodec codec;
  @Inject RequestContext context;
  @Inject ApiResponses responses;

  @ConfigProperty(name = "payments.body-limit", defaultValue = "65536")
  int bodyLimit;

  @POST
  @Path("/payments")
  @Consumes("*/*")
  @Produces("*/*")
  @Operation(summary = "Submit an atomic, idempotent JOD payment")
  public Response submit(@Context HttpHeaders headers, InputStream input) throws IOException {
    responses.negotiatedType();
    MediaType type = headers.getMediaType();
    String media = type == null ? "" : type.getType() + "/" + type.getSubtype();
    if (!Set.of("application/json", "application/xml").contains(media))
      throw new ApiFailure(
          415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json or application/xml.");
    String charset = type.getParameters().get("charset");
    if (charset != null && !charset.equalsIgnoreCase("utf-8"))
      throw new ApiFailure(
          415, "UNSUPPORTED_MEDIA_TYPE", "Only UTF-8 request encoding is supported.");
    byte[] body = input.readNBytes(bodyLimit + 1);
    if (body.length > bodyLimit)
      throw new ApiFailure(
          413, "REQUEST_TOO_LARGE", "The request body exceeds the configured limit.");
    var command = codec.parse(body, media);
    context.paymentId(command.paymentId().toString());
    Outcome outcome = service.transfer(command, context.principal(), context.correlationId());
    context.replay(outcome.replay());
    context.status(outcome.body().get("status").toString());
    context.code(outcome.body().getOrDefault("code", "NONE").toString());
    return responses.body(outcome.http(), outcome.body());
  }

  @GET
  @Path("/payments/{paymentId}")
  @Produces("*/*")
  @Operation(summary = "Retrieve an authorized persisted payment outcome")
  public Response find(@PathParam("paymentId") String id) {
    responses.negotiatedType();
    UUID parsed;
    try {
      if (!id.matches(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        throw new IllegalArgumentException();
      parsed = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiFailure(404, "PAYMENT_NOT_FOUND", "Payment was not found.");
    }
    context.paymentId(parsed.toString());
    var out = service.find(parsed, context.principal());
    context.status(out.body().get("status").toString());
    return responses.body(200, out.body());
  }

  @GET
  @Path("/accounts/{accountId}/balance")
  @Produces("*/*")
  @Operation(summary = "Read an authorized account balance")
  public Response balance(@PathParam("accountId") String id) {
    responses.negotiatedType();
    var result = service.balance(id, context.principal());
    context.status("OK");
    return responses.body(200, result);
  }

  @GET
  @Path("/admin/reconciliation")
  @Produces("*/*")
  @Operation(summary = "Report ledger and opening-baseline discrepancies (payments:admin)")
  public Response reconcile() {
    responses.negotiatedType();
    var issues = service.reconcile();
    context.status("OK");
    return responses.body(
        200,
        Map.of(
            "consistent",
            issues.isEmpty(),
            "issues",
            issues,
            "timestamp",
            java.time.Instant.now().toString()));
  }
}
