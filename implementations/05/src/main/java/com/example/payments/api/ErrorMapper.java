package com.example.payments.api;

import com.example.payments.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.time.Instant;
import java.util.*;
import org.jboss.logging.Logger;

@Provider
public class ErrorMapper implements ExceptionMapper<Throwable> {
  @Inject ResponseCodec codec;
  @Inject MeterRegistry metrics;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  public Response toResponse(Throwable failure) {
    int status = 500;
    String code = "INTERNAL_ERROR";
    List<Issue> issues = List.of();
    if (failure instanceof ApiException e) {
      status = e.http();
      code = e.code();
      issues = e.issues();
    } else if (failure instanceof io.quarkus.security.AuthenticationFailedException
        || failure instanceof io.quarkus.security.UnauthorizedException) {
      status = 401;
      code = "UNAUTHENTICATED";
    } else if (failure instanceof io.quarkus.security.ForbiddenException) {
      status = 403;
      code = "FORBIDDEN";
    } else if (failure instanceof WebApplicationException e) {
      status = e.getResponse().getStatus();
      code =
          switch (status) {
            case 400 -> "MALFORMED_MESSAGE";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "REQUEST_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
          };
    }
    Map<String, Object> body = new LinkedHashMap<>();
    if (failure instanceof ApiException e && e.paymentId() != null)
      body.put("paymentId", e.paymentId().toString());
    if (request.getProperty("paymentId") != null)
      body.put("paymentId", request.getProperty("paymentId"));
    body.put("status", "ERROR");
    body.put("code", code);
    body.put("message", "The request could not be completed.");
    body.put("issues", issues);
    body.put(
        "correlationId",
        Optional.ofNullable(request.getProperty("correlationId"))
            .orElse(UUID.randomUUID().toString()));
    body.put("timestamp", Instant.now().toString());
    Logger.getLogger(ErrorMapper.class)
        .infof(
            "correlationId=%s paymentId=%s status=ERROR code=%s latencyMs=%d",
            body.get("correlationId"),
            body.get("paymentId"),
            code,
            request.getProperty("started") instanceof Long start
                ? (System.nanoTime() - start) / 1_000_000
                : 0);
    if (status == 500) metrics.counter("payments.unexpected.failures").increment();
    Response result = codec.response(status, body);
    return status == 401
        ? Response.fromResponse(result).header("WWW-Authenticate", "Bearer").build()
        : result;
  }
}
