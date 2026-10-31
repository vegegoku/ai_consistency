package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.example.payments.observability.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.time.Instant;
import java.util.*;

@Provider
public class FailureMapper implements ExceptionMapper<Throwable> {
  @Inject MessageCodec codec;
  @Inject RequestContext context;
  @Inject MeterRegistry metrics;
  @Context HttpHeaders headers;

  @Override
  public Response toResponse(Throwable e) {
    int http = 500;
    String code = "INTERNAL_ERROR";
    String paymentId = null;
    List<ApiFailure.Issue> issues = List.of();
    if (e instanceof ApiFailure f) {
      http = f.http;
      code = f.code;
      paymentId = f.paymentId;
      issues = f.issues;
    } else if (e instanceof io.quarkus.security.AuthenticationFailedException
        || e instanceof io.quarkus.security.UnauthorizedException) {
      http = 401;
      code = "UNAUTHENTICATED";
    } else if (e instanceof WebApplicationException w) {
      http = w.getResponse().getStatus();
      code =
          switch (http) {
            case 400, 413 -> "MALFORMED_MESSAGE";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
          };
    }
    context.outcome(paymentId, code);
    var body = new LinkedHashMap<String, Object>();
    if (paymentId != null) body.put("paymentId", paymentId);
    body.put("status", "ERROR");
    body.put("code", code);
    body.put("message", "The request could not be completed.");
    body.put("issues", issues);
    body.put("correlationId", context.correlationId());
    body.put("timestamp", Instant.now().toString());
    boolean xml = false;
    try {
      xml = PaymentResource.responseXml(headers);
    } catch (Exception ignored) {
    }
    if (http == 500) metrics.counter("payments.unexpected_failures").increment();
    var r =
        Response.status(http)
            .type(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON)
            .entity(codec.encode(body, xml))
            .header("X-Correlation-ID", context.correlationId());
    if (http == 401) r.header("WWW-Authenticate", "Bearer");
    if (http == 429 || http == 503) r.header("Retry-After", http == 429 ? 60 : 1);
    return r.build();
  }
}
