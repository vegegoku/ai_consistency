package com.example.payments.api;

import com.example.payments.domain.Problem;
import com.example.payments.observability.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;
import org.jboss.logging.Logger;

@Provider
public class Errors implements ExceptionMapper<Throwable> {
  @Inject MessageCodec codec;
  @Inject RequestContext context;
  @Inject MeterRegistry metrics;

  public Response toResponse(Throwable e) {
    Problem p =
        e instanceof Problem a
            ? a
            : e instanceof WebApplicationException w
                ? new Problem(
                    w.getResponse().getStatus(),
                    w.getResponse().getStatus() == 415
                        ? "UNSUPPORTED_MEDIA_TYPE"
                        : w.getResponse().getStatus() == 406
                            ? "NOT_ACCEPTABLE"
                            : w.getResponse().getStatus() == 413
                                ? "PAYLOAD_TOO_LARGE"
                                : "MALFORMED_MESSAGE")
                : new Problem(500, "INTERNAL_ERROR");
    metrics.counter("payments.errors", "code", p.code).increment();
    Logger.getLogger(Errors.class)
        .infof(
            "correlationId=%s paymentId=%s status=ERROR code=%s latencyMs=%d",
            context.correlation(),
            p.paymentId,
            p.code,
            (System.nanoTime() - context.start()) / 1000000);
    Response.ResponseBuilder b =
        Response.status(p.http)
            .type(context.xml() ? "application/xml" : "application/json")
            .entity(codec.encode(p.body(context.correlation()), context.xml()));
    if (p.http == 503 || p.http == 429) b.header("Retry-After", "1");
    if (p.http == 401) b.header("WWW-Authenticate", "Bearer");
    return b.header("X-Correlation-ID", context.correlation()).build();
  }
}
