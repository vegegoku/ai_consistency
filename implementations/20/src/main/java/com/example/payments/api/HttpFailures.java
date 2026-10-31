package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Handles body rejection that occurs before a Jakarta REST request exists. */
@ApplicationScoped
public class HttpFailures {
  @Inject ObjectMapper mapper;

  @ConfigProperty(name = "payments.body-limit", defaultValue = "65536")
  int limit;

  void routes(@Observes Router router) {
    router
        .route()
        .order(Integer.MIN_VALUE)
        .handler(
            c -> {
              String length = c.request().getHeader("Content-Length");
              try {
                if (length != null && Long.parseLong(length) > limit) {
                  respond(c);
                  return;
                }
              } catch (NumberFormatException ignored) {
              }
              c.next();
            });
    router
        .route()
        .failureHandler(
            c -> {
              if (c.statusCode() != 413) {
                c.next();
                return;
              }
              respond(c);
            });
  }

  private void respond(io.vertx.ext.web.RoutingContext c) {
    UUID id = UUID.randomUUID();
    try {
      c.response()
          .setStatusCode(413)
          .putHeader("Content-Type", "application/json")
          .putHeader("X-Correlation-ID", id.toString())
          .putHeader("Cache-Control", "no-store")
          .end(
              mapper.writeValueAsString(
                  Responses.error(new ApiFailure(413, "REQUEST_TOO_LARGE"), id, null)));
    } catch (Exception e) {
      c.response().setStatusCode(413).end();
    }
  }
}
