package com.example.payments.api;

import com.example.payments.observability.Telemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Preserve the public error contract when the HTTP server rejects a body before REST dispatch. */
@ApplicationScoped
public class BodyLimitHandler {
  @ConfigProperty(name = "payments.body-limit", defaultValue = "65536")
  long limit;

  public void init(@Observes Router router) {
    router
        .route("/api/v1/*")
        .order(-3)
        .handler(
            event -> {
              String length = event.request().getHeader("Content-Length");
              if (length != null) {
                try {
                  if (Long.parseLong(length) > limit) {
                    tooLarge(event);
                    return;
                  }
                } catch (NumberFormatException ignored) {
                }
              }
              event.next();
            });
  }

  private void tooLarge(RoutingContext event) {
    String correlation = UUID.randomUUID().toString();
    var body = new LinkedHashMap<String, Object>();
    body.put("status", "ERROR");
    body.put("code", "REQUEST_TOO_LARGE");
    body.put("message", "The request body exceeds the configured limit.");
    body.put("issues", List.of());
    body.put("correlationId", correlation);
    body.put("timestamp", Instant.now().toString());
    String accept = event.request().getHeader("Accept");
    boolean xml =
        accept != null
            && accept.contains("application/xml")
            && !accept.contains("application/json");
    try {
      String serialized =
          xml
              ? new XmlMapper().writer().withRootName("response").writeValueAsString(body)
              : new ObjectMapper().writeValueAsString(body);
      event
          .response()
          .setStatusCode(413)
          .putHeader("Content-Type", xml ? "application/xml" : "application/json")
          .putHeader("Cache-Control", "no-store")
          .putHeader("X-Correlation-ID", correlation)
          .putHeader("Connection", "close")
          .end(serialized)
          .onComplete(result -> event.request().connection().close());
      Telemetry.event(correlation, null, "ERROR", "REQUEST_TOO_LARGE", 0, false, false);
    } catch (Exception failure) {
      event.fail(failure);
    }
  }
}
