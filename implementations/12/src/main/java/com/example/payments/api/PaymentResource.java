package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import com.example.payments.security.*;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/v1")
@Authenticated
public class PaymentResource {
  @Inject PaymentService service;
  @Inject MessageParser parser;
  @Inject Responses responses;
  @Inject JsonWebToken jwt;
  @Inject Limits limits;
  @Inject RequestContext context;
  @Inject RateLimiter limiter;

  private Caller caller() {
    var caller = Caller.from(jwt);
    limiter.check(caller);
    return caller;
  }

  @POST
  @Path("/payments")
  public Response submit(@Context HttpHeaders headers, @Context Request request, InputStream body)
      throws IOException {
    Caller caller = caller();
    caller.require("payments:write");
    MediaType responseMedia = responses.negotiate(request);
    MediaType type = headers.getMediaType();
    String media = type == null ? "" : type.getType() + "/" + type.getSubtype();
    var command = parser.parse(body.readNBytes(limits.bodyBytes + 1), media);
    MDCGuard.setPayment(command.paymentId().toString());
    var result = service.transfer(command, caller, context.correlationId());
    result.requireCompleted();
    return Response.fromResponse(
            responses.write(result.replay() ? 200 : 201, result.receipt(), responseMedia))
        .location(URI.create("/api/v1/payments/" + command.paymentId()))
        .build();
  }

  @GET
  @Path("/payments/{paymentId}")
  public Response find(@PathParam("paymentId") String id, @Context Request request) {
    Caller caller = caller();
    MediaType media = responses.negotiate(request);
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
      if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new ApiException(404, "PAYMENT_NOT_FOUND", "Payment not found.");
    }
    MDCGuard.setPayment(uuid.toString());
    var result = service.find(uuid, caller);
    if (result.rejectionCode() != null) {
      try {
        result.requireCompleted();
      } catch (ApiException e) {
        return Response.fromResponse(responses.error(e, media)).status(200).build();
      }
    }
    return responses.write(200, result.receipt(), media);
  }

  @GET
  @Path("/accounts/{accountId}/balance")
  public Response balance(@PathParam("accountId") String account, @Context Request request) {
    Caller caller = caller();
    return responses.write(200, service.balance(account, caller), responses.negotiate(request));
  }

  private static class MDCGuard {
    static void setPayment(String id) {
      org.jboss.logging.MDC.put("paymentId", id);
    }
  }
}
