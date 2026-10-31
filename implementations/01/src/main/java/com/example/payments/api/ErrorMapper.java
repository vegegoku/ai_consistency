package com.example.payments.api;

import com.example.payments.domain.*;
import com.example.payments.observability.RequestContext;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;

@Provider
public class ErrorMapper implements ExceptionMapper<Exception> {
  @Inject RequestContext context;
  @Context HttpHeaders headers;

  public Response toResponse(Exception error) {
    PaymentException e;
    if (error instanceof PaymentException known) e = known;
    else if (error instanceof UnauthorizedException || error instanceof NotAuthorizedException)
      e = new PaymentException(401, "UNAUTHENTICATED");
    else if (error instanceof ForbiddenException
        || error instanceof jakarta.ws.rs.ForbiddenException)
      e = new PaymentException(403, "FORBIDDEN");
    else if (error instanceof WebApplicationException web) {
      int http = web.getResponse().getStatus();
      String code =
          switch (http) {
            case 400 -> "MALFORMED_MESSAGE";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "TEMPORARILY_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
          };
      e = new PaymentException(http, code);
    } else e = new PaymentException(500, "INTERNAL_ERROR");
    context.code(e.code());
    if (e.paymentId() != null) context.paymentId(e.paymentId());
    String media = "application/json";
    try {
      media = ResponseCodec.negotiate(headers);
    } catch (Exception ignored) {
    }
    return ResponseCodec.response(e.http(), Errors.body(e, context.correlation()), media)
        .header("X-Correlation-ID", context.correlation())
        .build();
  }
}
