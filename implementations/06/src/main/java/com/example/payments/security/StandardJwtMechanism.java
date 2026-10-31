package com.example.payments.security;

import io.quarkus.smallrye.jwt.runtime.auth.*;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;

/**
 * Preserve Quarkus JWT validation while giving pre-resource authentication failures the API error
 * contract.
 */
@Alternative
@Priority(1)
@jakarta.inject.Singleton
public class StandardJwtMechanism extends JWTAuthMechanism {
  @Inject
  public StandardJwtMechanism(SmallRyeJwtConfig config) {
    super(config);
  }

  @Override
  public Uni<Boolean> sendChallenge(RoutingContext context) {
    String correlation = UUID.randomUUID().toString();
    String timestamp = Instant.now().toString();
    boolean xml =
        context.request().getHeader("Accept") != null
            && context.request().getHeader("Accept").contains("application/xml");
    String body =
        xml
            ? "<response><status>ERROR</status><code>UNAUTHENTICATED</code><message>Authentication"
                + " is required.</message><issues/><correlationId>"
                + correlation
                + "</correlationId><timestamp>"
                + timestamp
                + "</timestamp></response>"
            : "{\"status\":\"ERROR\",\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication is"
                + " required.\",\"issues\":[],\"correlationId\":\""
                + correlation
                + "\",\"timestamp\":\""
                + timestamp
                + "\"}";
    context
        .response()
        .setStatusCode(401)
        .putHeader("Content-Type", xml ? "application/xml" : "application/json")
        .putHeader("WWW-Authenticate", "Bearer")
        .putHeader("X-Correlation-ID", correlation);
    return Uni.createFrom()
        .completionStage(context.response().end(body).toCompletionStage())
        .replaceWith(true);
  }
}
