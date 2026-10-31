package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import io.quarkus.security.AuthenticationFailedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.util.UUID;

@Provider
public class FailureMapper implements ExceptionMapper<Throwable> {
  @Context HttpHeaders headers;
  @Inject ContainerRequestContext context;

  public Response toResponse(Throwable error) {
    ApiFailure e =
        error instanceof ApiFailure a
            ? a
            : error instanceof AuthenticationFailedException
                ? new ApiFailure(401, "UNAUTHENTICATED")
                : error instanceof jakarta.ws.rs.WebApplicationException w
                    ? new ApiFailure(
                        w.getResponse().getStatus(),
                        w.getResponse().getStatus() == 404 ? "NOT_FOUND" : "MALFORMED_MESSAGE")
                    : new ApiFailure(500, "INTERNAL_ERROR");
    context.setProperty("outcomeCode", e.code);
    UUID id = context.getProperty("correlationId") instanceof UUID u ? u : UUID.randomUUID();
    String media = "application/json";
    try {
      media = Responses.negotiate(headers);
    } catch (Exception ignored) {
    }
    return Responses.render(
        e.http, Responses.error(e, id, (String) context.getProperty("paymentId")), media, id);
  }
}
