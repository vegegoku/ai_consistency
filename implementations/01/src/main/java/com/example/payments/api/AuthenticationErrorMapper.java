package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.example.payments.observability.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;

@Provider
public class AuthenticationErrorMapper
    implements ExceptionMapper<io.quarkus.security.AuthenticationFailedException> {
  @Inject RequestContext context;
  @Context HttpHeaders headers;

  public Response toResponse(io.quarkus.security.AuthenticationFailedException e) {
    context.code("UNAUTHENTICATED");
    return ResponseCodec.response(
            401,
            Errors.body(new PaymentException(401, "UNAUTHENTICATED"), context.correlation()),
            ResponseCodec.errorMedia(headers))
        .header("WWW-Authenticate", "Bearer")
        .header("X-Correlation-ID", context.correlation())
        .build();
  }
}
