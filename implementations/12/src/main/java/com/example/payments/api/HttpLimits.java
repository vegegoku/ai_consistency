package com.example.payments.api;

import com.example.payments.application.Limits;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;

/** Preserve the API error envelope for requests rejected before REST routing. */
@ApplicationScoped
public class HttpLimits {
  @Inject Limits limits;
  @Inject io.micrometer.core.instrument.MeterRegistry metrics;
  private final ObjectMapper json = new ObjectMapper();
  private final XmlMapper xml = new XmlMapper();

  void install(@Observes Router router) {
    router
        .route("/api/*")
        .order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 2)
        .handler(
            context -> {
              String length = context.request().getHeader("Content-Length");
              if (length != null) {
                try {
                  if (Long.parseLong(length) > limits.bodyBytes) {
                    reject(context);
                    return;
                  }
                } catch (NumberFormatException ignored) {
                }
              }
              context.next();
            });
    router
        .post("/api/v1/payments")
        .order(RouteConstants.ROUTE_ORDER_UPLOAD_LIMIT - 1)
        .handler(
            io.vertx.ext.web.handler.BodyHandler.create()
                .setBodyLimit(limits.bodyBytes)
                .setHandleFileUploads(false))
        .failureHandler(
            context -> {
              if (context.statusCode() == 413) reject(context);
              else context.next();
            });
  }

  private void reject(RoutingContext context) {
    UUID correlation = UUID.randomUUID();
    metrics
        .counter(
            "payments.http.requests", "method", context.request().method().name(), "status", "413")
        .increment();
    metrics.counter("payments.errors", "code", "PAYLOAD_TOO_LARGE").increment();
    org.jboss.logging.Logger.getLogger(HttpLimits.class)
        .infov("correlationId={0} status=413 code=PAYLOAD_TOO_LARGE", correlation);
    var body = new LinkedHashMap<String, Object>();
    body.put("status", "ERROR");
    body.put("code", "PAYLOAD_TOO_LARGE");
    body.put("message", "The request body exceeds its limit.");
    body.put("issues", List.of());
    body.put("correlationId", correlation.toString());
    body.put("timestamp", Instant.now().toString());
    String accept = context.request().getHeader("Accept");
    boolean useXml = accept != null && accept.startsWith("application/xml");
    try {
      context
          .response()
          .setStatusCode(413)
          .putHeader("Content-Type", useXml ? "application/xml" : "application/json")
          .putHeader("X-Correlation-ID", correlation.toString())
          .putHeader("Cache-Control", "no-store")
          .putHeader("X-Content-Type-Options", "nosniff")
          .putHeader("Connection", "close");
      context
          .response()
          .end(
              useXml
                  ? xml.writer().withRootName("response").writeValueAsString(body)
                  : json.writeValueAsString(body));
    } catch (Exception e) {
      context.fail(e);
    }
  }
}
