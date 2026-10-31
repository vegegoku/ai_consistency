package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import com.example.payments.persistence.Reconciliation;
import com.example.payments.security.CallerPolicy;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
@Blocking
public class PaymentResource {
  @Inject PaymentService service;
  @Inject MessageCodec codec;
  @Inject CallerPolicy caller;
  @Inject RequestContext context;
  @Inject Reconciliation reconciliation;

  @ConfigProperty(name = "payment.max-amount", defaultValue = "10000.000")
  String maximum;

  @ConfigProperty(name = "payment.max-future-seconds", defaultValue = "300")
  long future;

  @ConfigProperty(name = "payment.max-age-seconds", defaultValue = "2592000")
  long age;

  @ConfigProperty(name = "payment.max-body-bytes", defaultValue = "65536")
  int bodyLimit;

  @ConfigProperty(name = "payment.rate-limit-per-minute", defaultValue = "6000")
  int rate;

  @ConfigProperty(name = "payment.retention.minimum-years", defaultValue = "7")
  int retentionYears;

  @ConfigProperty(name = "payment.audit-log-retention-days", defaultValue = "90")
  int auditDays;

  @POST
  @Path("/payments")
  @Consumes("*/*")
  public Response submit(@Context HttpHeaders headers, InputStream input) throws IOException {
    String actor = caller.require("payments:write");
    service.rateLimit(actor, rate);
    boolean xml = responseXml(headers);
    var type = headers.getMediaType();
    if (type == null
        || !(type.getType().equals("application") && type.getSubtype().equals("json")
            || type.getType().equals("application") && type.getSubtype().equals("xml")))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] bytes = input.readNBytes(bodyLimit + 1);
    if (bytes.length == 0 || bytes.length > bodyLimit)
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    var command =
        PaymentCommand.validate(
            codec.parse(
                bytes, type.getType().equals("application") && type.getSubtype().equals("xml")),
            Clock.systemUTC(),
            maximum,
            future,
            age);
    context.outcome(command.paymentId().toString(), null);
    var result = service.transfer(command, actor, context.correlationId());
    var response = respond(result.http(), result.body(), xml);
    if (result.http() == 201)
      response.header("Location", "/api/v1/payments/" + command.paymentId());
    return response.build();
  }

  @GET
  @Path("/payments/{id}")
  public Response lookup(@PathParam("id") String id, @Context HttpHeaders headers) {
    String actor = caller.require("payments:read");
    service.rateLimit(actor, rate);
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiFailure(404, "PAYMENT_NOT_FOUND");
    }
    return respond(200, service.lookup(uuid, actor).body(), responseXml(headers)).build();
  }

  @GET
  @Path("/accounts/{id}/balance")
  public Response balance(@PathParam("id") String id, @Context HttpHeaders headers) {
    String actor = caller.require("payments:read");
    service.rateLimit(actor, rate);
    return respond(200, service.balance(id, actor), responseXml(headers)).build();
  }

  @GET
  @Path("/admin/reconciliation")
  public Response reconciliation(@Context HttpHeaders headers) {
    caller.require("payments:admin");
    return respond(
            200,
            Map.of(
                "issues",
                reconciliation.report(),
                "retentionMinimumYears",
                retentionYears,
                "auditLogRetentionDays",
                auditDays),
            responseXml(headers))
        .build();
  }

  Response.ResponseBuilder respond(int status, Map<String, Object> payload, boolean xml) {
    var body = new LinkedHashMap<>(payload);
    context.outcome((String) payload.get("paymentId"), (String) payload.get("code"));
    if (status >= 400) {
      body.putIfAbsent("correlationId", context.correlationId());
      body.putIfAbsent("timestamp", java.time.Instant.now().toString());
    }
    return Response.status(status)
        .type(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON)
        .header("X-Correlation-ID", context.correlationId())
        .entity(codec.encode(body, xml));
  }

  static boolean responseXml(HttpHeaders h) {
    var types = h.getAcceptableMediaTypes();
    for (var t : types) {
      if ("0".equals(t.getParameters().get("q"))) continue;
      if (t.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return false;
      if (t.isCompatible(MediaType.APPLICATION_XML_TYPE)) return true;
    }
    throw new ApiFailure(406, "NOT_ACCEPTABLE");
  }
}
