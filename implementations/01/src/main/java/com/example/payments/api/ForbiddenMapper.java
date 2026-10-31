package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.example.payments.observability.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;

@Provider
public class ForbiddenMapper implements ExceptionMapper<io.quarkus.security.ForbiddenException> {
  @Inject RequestContext context;
  @Context HttpHeaders headers;

  public Response toResponse(io.quarkus.security.ForbiddenException e) {
    context.code("FORBIDDEN");
    return ResponseCodec.response(
            403,
            Errors.body(new PaymentException(403, "FORBIDDEN"), context.correlation()),
            ResponseCodec.errorMedia(headers))
        .header("X-Correlation-ID", context.correlation())
        .build();
  }
}
