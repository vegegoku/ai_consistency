package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import io.agroal.api.AgroalDataSource;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.sql.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@Path("/api/v1")
@Produces("*/*")
public class PaymentResource {
  @Inject PaymentService service;
  @Inject PaymentParser parser;
  @Inject SecurityIdentity identity;
  @Inject JsonWebToken jwt;
  @Inject AgroalDataSource db;

  @ConfigProperty(name = "payments.rate-per-minute", defaultValue = "600")
  int rate;

  @ConfigProperty(name = "payments.body-limit", defaultValue = "65536")
  int bodyLimit;

  @Context HttpHeaders headers;
  @Context jakarta.ws.rs.container.ContainerRequestContext context;

  @POST
  @Path("/payments")
  public Response submit(InputStream input) {
    String media = Responses.negotiate(headers);
    Caller caller = caller("payments:write");
    String type =
        headers.getMediaType() == null
            ? ""
            : headers.getMediaType().getType() + "/" + headers.getMediaType().getSubtype();
    if (!type.equals("application/json") && !type.equals("application/xml"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    String body;
    try {
      byte[] bytes = input.readNBytes(bodyLimit + 1);
      if (bytes.length > bodyLimit) throw new ApiFailure(413, "REQUEST_TOO_LARGE");
      body =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (IOException e) {
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    }
    Map<String, String> values = parser.parse(body, type.equals("application/xml"));
    try {
      UUID id = UUID.fromString(values.get("paymentId"));
      if (id.toString().equalsIgnoreCase(values.get("paymentId")))
        context.setProperty("paymentId", id.toString());
    } catch (Exception ignored) {
    }
    PaymentCommand command = parser.validate(values);
    PaymentService.Outcome o = service.transfer(command, caller, correlation());
    context.setProperty("outcomeCode", o.body().getOrDefault("code", "COMPLETED"));
    Response r = Responses.render(o.http(), o.body(), media, correlation());
    if (o.http() == 201)
      return Response.fromResponse(r)
          .header("Location", "/api/v1/payments/" + command.paymentId())
          .build();
    return r;
  }

  @GET
  @Path("/payments/{id}")
  public Response get(@PathParam("id") String id) {
    String media = Responses.negotiate(headers);
    Caller caller = caller("payments:read");
    UUID payment;
    try {
      payment = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiFailure(404, "PAYMENT_NOT_FOUND");
    }
    var result = service.get(payment, caller);
    context.setProperty("paymentId", payment.toString());
    return Responses.render(result.http(), result.body(), media, correlation());
  }

  @GET
  @Path("/accounts/{id}/balance")
  public Response balance(@PathParam("id") String id) {
    String media = Responses.negotiate(headers);
    return Responses.render(
        200, service.balance(id, caller("accounts:read")), media, correlation());
  }

  @GET
  @Path("/admin/reconciliation")
  public Response reconciliation() {
    String media = Responses.negotiate(headers);
    Caller c = caller("payments:admin");
    if (!c.admin()) throw new ApiFailure(403, "FORBIDDEN");
    return Responses.render(200, Map.of("issues", service.reconcile()), media, correlation());
  }

  private Caller caller(String required) {
    if (identity.isAnonymous()) throw new ApiFailure(401, "UNAUTHENTICATED");
    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) throw new ApiFailure(401, "UNAUTHENTICATED");
    String scope = jwt.getClaim("scope");
    Set<String> scopes =
        new HashSet<>(Arrays.asList(scope == null ? new String[0] : scope.split(" ")));
    if (!scopes.contains(required)) throw new ApiFailure(403, "FORBIDDEN");
    String customer = jwt.getClaim("customer_id");
    Caller caller = new Caller(subject, customer, jwt.getGroups().contains("admin"));
    rate(caller);
    return caller;
  }

  private UUID correlation() {
    return (UUID) context.getProperty("correlationId");
  }

  private void rate(Caller c) {
    try (Connection conn = db.getConnection();
        PreparedStatement s =
            conn.prepareStatement(
                "INSERT INTO request_rates(principal,minute,requests) VALUES(?,?,1) ON CONFLICT(principal,minute) DO UPDATE SET requests=request_rates.requests+1 WHERE request_rates.requests<? RETURNING requests")) {
      s.setQueryTimeout(3);
      s.setString(1, c.subject());
      s.setLong(2, System.currentTimeMillis() / 60000);
      s.setInt(3, rate);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new ApiFailure(429, "RATE_LIMITED");
      }
    } catch (SQLException e) {
      throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
  }
}
