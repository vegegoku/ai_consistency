package com.example.payments.api;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;

/**
 * HTTP body limits are enforced before JAX-RS; keep that rejection in the public error contract.
 */
@ApplicationScoped
public class HttpErrorHandler {
  @Inject MessageCodec codec;

  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "payment.max-body-bytes",
      defaultValue = "65536")
  long bodyLimit;

  void configure(@Observes Router router) {
    router
        .route()
        .order(-4)
        .handler(
            ctx -> {
              String length = ctx.request().getHeader("Content-Length");
              if (length != null)
                try {
                  if (Long.parseLong(length) > bodyLimit) {
                    reject(ctx);
                    return;
                  }
                } catch (NumberFormatException ignored) {
                }
              ctx.next();
            });
    router
        .route()
        .order(-3)
        .handler(
            io.vertx.ext.web.handler.BodyHandler.create()
                .setBodyLimit(bodyLimit)
                .setHandleFileUploads(false));
    router
        .route()
        .order(-3)
        .failureHandler(
            ctx -> {
              if (ctx.statusCode() == 413) reject(ctx);
              else ctx.next();
            });
    router.errorHandler(413, this::reject);
  }

  private void reject(io.vertx.ext.web.RoutingContext ctx) {
    String id = UUID.randomUUID().toString();
    String accept = ctx.request().getHeader("Accept");
    boolean xml = accept != null && accept.contains("application/xml");
    var body =
        Map.of(
            "status",
            "ERROR",
            "code",
            "MALFORMED_MESSAGE",
            "message",
            "The request body exceeds the configured size limit.",
            "issues",
            List.of(),
            "correlationId",
            id,
            "timestamp",
            Instant.now().toString());
    ctx.response()
        .setStatusCode(413)
        .putHeader("Content-Type", xml ? "application/xml" : "application/json")
        .putHeader("X-Correlation-ID", id)
        .putHeader("Cache-Control", "no-store")
        .end(codec.encode(body, xml));
  }
}
