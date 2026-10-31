package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.*;

@Provider
public class FailureMapper implements ExceptionMapper<Exception> {
  @Inject Responses responses;
  @Context HttpHeaders headers;
  @Context ContainerRequestContext request;

  public Response toResponse(Exception error) {
    ApiFailure e;
    if (error instanceof ApiFailure a) e = a;
    else if (error instanceof jakarta.ws.rs.WebApplicationException w) {
      int status = w.getResponse().getStatus();
      e =
          new ApiFailure(
              status,
              switch (status) {
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                case 401 -> "UNAUTHENTICATED";
                case 404 -> "NOT_FOUND";
                default -> "MALFORMED_MESSAGE";
              });
    } else if (error instanceof io.quarkus.security.AuthenticationFailedException
        || error instanceof io.quarkus.security.UnauthorizedException)
      e = new ApiFailure(401, "UNAUTHENTICATED");
    else e = new ApiFailure(500, "INTERNAL_ERROR");
    String correlation =
        Objects.toString(request.getProperty("correlation"), UUID.randomUUID().toString());
    Response out =
        responses.send(
            e.http,
            responses.error(e, correlation),
            e.http == 406 ? "application/json" : headers.getHeaderString("Accept"),
            correlation);
    return e.http == 503 ? Response.fromResponse(out).header("Retry-After", "2").build() : out;
  }
}
