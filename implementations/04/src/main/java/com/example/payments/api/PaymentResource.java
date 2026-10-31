package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import io.micrometer.core.instrument.Metrics;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
public class PaymentResource {
  @Inject PaymentService service;
  @Inject ResponseCodec codec;
  @Context HttpHeaders headers;
  @Context ContainerRequestContext request;

  @ConfigProperty(name = "payments.max-body-bytes", defaultValue = "65536")
  int maxBody;

  @ConfigProperty(name = "payments.maximum", defaultValue = "10000.000")
  String maxAmount;

  @ConfigProperty(name = "payments.future-seconds", defaultValue = "300")
  long future;

  @ConfigProperty(name = "payments.max-age-seconds", defaultValue = "2592000")
  long age;

  private Caller caller() {
    return (Caller) request.getProperty("caller");
  }

  private String correlation() {
    return Objects.toString(request.getProperty("correlation"), UUID.randomUUID().toString());
  }

  @POST
  @Path("/payments")
  public Response submit(InputStream body) throws IOException {
    String type = codec.negotiate(headers);
    MediaType content = headers.getMediaType();
    if (content == null
        || !(content.isCompatible(MediaType.APPLICATION_JSON_TYPE)
            || content.isCompatible(MediaType.APPLICATION_XML_TYPE)))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] bytes = body.readNBytes(maxBody + 1);
    if (bytes.length > maxBody) throw new ApiException(413, "MESSAGE_TOO_LARGE");
    if (bytes.length == 0) throw new ApiException(400, "MALFORMED_MESSAGE");
    var p =
        new PaymentParser(
                new BigDecimal(maxAmount), Duration.ofSeconds(future), Duration.ofSeconds(age))
            .parse(bytes, content.isCompatible(MediaType.APPLICATION_XML_TYPE));
    request.setProperty("paymentId", p.paymentId().toString());
    var outcome = service.transfer(p, caller(), UUID.fromString(correlation()));
    try {
      Metrics.counter(
              outcome.replay() ? "payment_replays" : "payment_outcomes",
              "status",
              outcome.status(),
              "code",
              Objects.toString(outcome.code(), "NONE"))
          .increment();
    } catch (Exception ignored) {
    }
    Response response = receipt(outcome, outcome.replay() ? 200 : 201, type);
    return outcome.code() == null
        ? Response.fromResponse(response)
            .header("Location", "/api/v1/payments/" + p.paymentId())
            .build()
        : response;
  }

  @GET
  @Path("/payments/{paymentId}")
  public Response get(@PathParam("paymentId") String id) {
    String type = codec.negotiate(headers);
    UUID payment;
    try {
      payment = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiException(404, "NOT_FOUND");
    }
    request.setProperty("paymentId", payment.toString());
    Response response = receipt(service.get(payment, caller()), 200, type);
    return Response.fromResponse(response).status(200).build();
  }

  @GET
  @Path("/accounts/{accountId}/balance")
  public Response balance(@PathParam("accountId") String id) {
    return codec.response(
        service.balance(id, caller()), 200, codec.negotiate(headers), correlation());
  }

  @GET
  @Path("/admin/reconciliation")
  public Response reconcile() {
    if (!caller().admin()) throw new ApiException(403, "FORBIDDEN");
    var issues = service.reconcile();
    return codec.response(
        Map.of("consistent", issues.isEmpty(), "issues", issues, "timestamp", Instant.now()),
        200,
        codec.negotiate(headers),
        correlation());
  }

  private Response receipt(Outcome o, int status, String type) {
    request.setProperty("paymentStatus", o.status());
    if (o.code() != null) {
      request.setProperty("errorCode", o.code());
      int http =
          switch (o.code()) {
            case "FORBIDDEN" -> 403;
            case "ACCOUNT_NOT_FOUND" -> 404;
            default -> 409;
          };
      return codec.response(
          Map.of(
              "paymentId",
              o.paymentId(),
              "status",
              "REJECTED",
              "code",
              o.code(),
              "message",
              "The payment could not be completed.",
              "issues",
              List.of(
                  new ApiException.Issue(
                      o.code().equals("INSUFFICIENT_FUNDS") ? "amount" : "payment",
                      o.code(),
                      "The payment could not be completed.")),
              "correlationId",
              correlation(),
              "timestamp",
              o.processedAt()),
          http,
          type,
          correlation());
    }
    return codec.response(
        Map.of(
            "paymentId",
            o.paymentId(),
            "status",
            o.status(),
            "fromAccount",
            o.fromAccount(),
            "toAccount",
            o.toAccount(),
            "amount",
            o.amount(),
            "currency",
            o.currency(),
            "processedAt",
            o.processedAt()),
        status,
        type,
        correlation());
  }
}
