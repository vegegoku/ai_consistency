package com.example.payments.api;

import com.example.payments.domain.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import org.jboss.logging.Logger;

@Provider
public class ExceptionHandler implements ExceptionMapper<Throwable> {
  @Inject Responses responses;
  @Inject MeterRegistry metrics;
  @Context Request request;

  public Response toResponse(Throwable failure) {
    ApiException error;
    if (failure instanceof ApiException e) error = e;
    else if (failure instanceof UnauthorizedException
        || failure instanceof AuthenticationFailedException)
      error = new ApiException(401, "UNAUTHENTICATED", "A valid bearer token is required.");
    else if (failure instanceof jakarta.ws.rs.ForbiddenException
        || failure instanceof io.quarkus.security.ForbiddenException)
      error = new ApiException(403, "FORBIDDEN", "The operation is not permitted.");
    else if (failure instanceof NotFoundException)
      error = new ApiException(404, "NOT_FOUND", "Resource not found.");
    else if (failure instanceof WebApplicationException web) {
      int status = web.getResponse().getStatus();
      String code =
          switch (status) {
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            default -> "MALFORMED_MESSAGE";
          };
      error = new ApiException(status, code, "The request could not be processed.");
    } else {
      metrics.counter("payments.unexpected.failures").increment();
      Logger.getLogger(ExceptionHandler.class).error("Unexpected request processing failure");
      error =
          new ApiException(
              500,
              "INTERNAL_ERROR",
              "An unexpected processing error occurred. Query status before retrying with the same"
                  + " payment ID.");
    }
    if (error.paymentId() != null)
      org.jboss.logging.MDC.put("paymentId", error.paymentId().toString());
    metrics.counter("payments.errors", "code", error.code()).increment();
    MediaType media = responses.errorMedia(request);
    return responses.error(error, media);
  }
}
