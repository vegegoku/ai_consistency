package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.security.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/api/v1")
public class PaymentResource {
  @Inject PaymentService service;
  @Inject Access access;
  @Inject RateLimiter limiter;
  @Inject ResponseCodec codec;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @ConfigProperty(name = "payments.maximum")
  BigDecimal maximum;

  @ConfigProperty(name = "payments.max-future-seconds")
  long future;

  @ConfigProperty(name = "payments.max-age-seconds")
  long age;

  @ConfigProperty(name = "payments.body-limit")
  int bodyLimit;

  private Caller caller(String scope) {
    var c = access.caller(scope);
    limiter.check(c.subject());
    codec.check();
    return c;
  }

  @POST
  @Path("/payments")
  @Operation(summary = "Submit a JSON or XML internal JOD transfer")
  public Response submit(@HeaderParam("Content-Type") String type, InputStream body)
      throws IOException {
    Caller caller = caller("payments:write");
    MediaType media;
    try {
      media = MediaType.valueOf(type);
    } catch (Exception e) {
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    }
    boolean xml =
        media.getType().equalsIgnoreCase("application")
            && media.getSubtype().equalsIgnoreCase("xml");
    if (!xml
        && !(media.getType().equalsIgnoreCase("application")
            && media.getSubtype().equalsIgnoreCase("json")))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] bytes = body.readNBytes(bodyLimit + 1);
    if (bytes.length > bodyLimit) throw new ApiException(413, "REQUEST_TOO_LARGE");
    PaymentCommand command =
        new MessageParser(maximum, Duration.ofSeconds(future), Duration.ofSeconds(age))
            .parse(bytes, xml);
    request.setProperty("paymentId", command.paymentId().toString());
    Outcome outcome =
        service.transfer(command, caller, String.valueOf(request.getProperty("correlationId")));
    Response response = codec.response(outcome.httpStatus(), outcome.body());
    return outcome.httpStatus() == 201
        ? Response.fromResponse(response)
            .header("Location", "/api/v1/payments/" + command.paymentId())
            .build()
        : response;
  }

  @GET
  @Path("/payments/{paymentId}")
  @Operation(summary = "Retrieve an authorized stored completed or rejected outcome")
  public Response get(@PathParam("paymentId") String id) {
    Caller caller = caller("payments:read");
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiException(404, "NOT_FOUND");
    }
    return codec.response(200, service.find(uuid, caller).body());
  }

  @GET
  @Path("/accounts/{accountId}/balance")
  @Operation(summary = "Read the caller's authorized persisted available balance")
  public Response balance(@PathParam("accountId") String id) {
    return codec.response(200, service.balance(id, caller("payments:read")));
  }

  @GET
  @Path("/admin/reconciliation")
  @Operation(summary = "Privileged consistent-snapshot financial reconciliation report")
  public Response reconcile() {
    caller("payments:admin");
    return codec.response(200, Map.of("issues", service.reconcile()));
  }
}
