package com.example.payments.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;

@Provider
public class FailureMapper implements ExceptionMapper<Throwable> {
  @Inject ApiResponses responses;

  public Response toResponse(Throwable error) {
    if (error instanceof ApiFailure f) return responses.error(f);
    if (error instanceof WebApplicationException w) {
      int status = w.getResponse().getStatus();
      String code =
          switch (status) {
            case 400 -> "MALFORMED_MESSAGE";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "REQUEST_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
          };
      return responses.error(new ApiFailure(status, code, "The request could not be processed."));
    }
    return responses.error(
        new ApiFailure(500, "INTERNAL_ERROR", "The request could not be processed."));
  }
}
