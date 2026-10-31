package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.example.payments.observability.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.time.Instant;
import java.util.*;

@Provider
public class Errors implements ExceptionMapper<Throwable> {
  @Inject RequestContext context;
  @Inject Representations representations;
  @Context HttpHeaders headers;

  public Response toResponse(Throwable error) {
    PaymentException e;
    if (error instanceof PaymentException p) e = p;
    else if (error instanceof io.quarkus.security.UnauthorizedException
        || error instanceof io.quarkus.security.AuthenticationFailedException)
      e = new PaymentException(401, "UNAUTHENTICATED");
    else if (error instanceof WebApplicationException w)
      e =
          new PaymentException(
              w.getResponse().getStatus(),
              switch (w.getResponse().getStatus()) {
                case 401 -> "UNAUTHENTICATED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "MALFORMED_MESSAGE";
              });
    else e = new PaymentException(500, "INTERNAL_ERROR");
    context.code = e.code;
    context.status = "ERROR";
    var body = new LinkedHashMap<String, Object>();
    String id = e.paymentId != null ? e.paymentId : context.paymentId;
    if (id != null) {
      context.paymentId = id;
      body.put("paymentId", id);
    }
    body.put("status", "ERROR");
    body.put("code", e.code);
    body.put("message", e.getMessage());
    body.put("issues", e.issues);
    body.put("correlationId", context.correlationId);
    body.put("timestamp", Instant.now().toString());
    Response response;
    try {
      response = representations.response(e.http, body, headers);
    } catch (PaymentException unsupported) {
      response =
          Response.status(406)
              .type("application/json")
              .entity(
                  "{\"status\":\"ERROR\",\"code\":\"NOT_ACCEPTABLE\",\"message\":\"Unsupported"
                      + " response type\",\"issues\":[],\"correlationId\":\""
                      + context.correlationId
                      + "\",\"timestamp\":\""
                      + Instant.now()
                      + "\"}")
              .build();
    }
    var builder = Response.fromResponse(response);
    if (e.http == 401) builder.header("WWW-Authenticate", "Bearer");
    if (e.http == 503 || e.http == 429) builder.header("Retry-After", e.http == 429 ? "60" : "2");
    return builder.build();
  }
}
