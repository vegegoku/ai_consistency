package com.example.payments.api;

import com.example.payments.domain.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.time.Instant;
import java.util.*;

@Provider
public class Errors implements ExceptionMapper<Throwable> {
  @Inject ResponseCodec codec;
  @Context HttpHeaders headers;
  @Context ContainerRequestContext request;

  public Response toResponse(Throwable error) {
    ApiException e;
    if (error instanceof ApiException a) e = a;
    else if (error instanceof jakarta.ws.rs.WebApplicationException w) {
      int status = w.getResponse().getStatus();
      e =
          new ApiException(
              status,
              switch (status) {
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "MALFORMED_MESSAGE";
              });
    } else e = new ApiException(500, "INTERNAL_ERROR");
    if (e.http == 500)
      try {
        io.micrometer.core.instrument.Metrics.counter("payment_unexpected_failures").increment();
      } catch (Exception ignored) {
      }
    String correlation =
        Objects.toString(request.getProperty("correlation"), UUID.randomUUID().toString());
    request.setProperty("errorCode", e.code);
    Map<String, Object> body = new LinkedHashMap<>();
    if (e.paymentId != null) request.setProperty("paymentId", e.paymentId.toString());
    if (request.getProperty("paymentId") != null)
      body.put("paymentId", request.getProperty("paymentId"));
    body.put("status", "ERROR");
    body.put("code", e.code);
    body.put(
        "message",
        e.http == 503
            ? "Temporarily unavailable; retry or query using the same payment ID."
            : "The request could not be completed.");
    body.put("issues", e.issues);
    body.put("correlationId", correlation);
    body.put("timestamp", Instant.now());
    String type = "application/json";
    try {
      type = codec.negotiate(headers);
    } catch (Exception ignored) {
    }
    return codec.response(body, e.http, type, correlation);
  }
}
