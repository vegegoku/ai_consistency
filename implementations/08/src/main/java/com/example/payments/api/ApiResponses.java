package com.example.payments.api;

import com.example.payments.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.util.*;

@RequestScoped
public class ApiResponses {
  @Inject RequestContext context;
  @Context HttpHeaders headers;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final XmlMapper XML = new XmlMapper();

  public String negotiatedType() {
    List<MediaType> requested;
    try {
      requested = headers.getAcceptableMediaTypes();
    } catch (Exception e) {
      throw new ApiFailure(406, "NOT_ACCEPTABLE", "Only JSON or XML responses are available.");
    }
    for (MediaType m : requested) {
      try {
        if (Double.parseDouble(m.getParameters().getOrDefault("q", "1")) <= 0) continue;
      } catch (NumberFormatException e) {
        continue;
      }
      if (m.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return "application/json";
      if (m.isCompatible(MediaType.APPLICATION_XML_TYPE)) return "application/xml";
    }
    throw new ApiFailure(406, "NOT_ACCEPTABLE", "Only JSON or XML responses are available.");
  }

  public Response body(int status, Object body) {
    return serialize(status, body, negotiatedType());
  }

  public Response error(ApiFailure failure) {
    context.status("ERROR");
    context.code(failure.code);
    if (failure.paymentId != null) context.paymentId(failure.paymentId);
    var body = new LinkedHashMap<String, Object>();
    if (context.paymentId() != null) body.put("paymentId", context.paymentId());
    body.put("status", "ERROR");
    body.put("code", failure.code);
    body.put("message", failure.getMessage());
    body.put("issues", failure.issues);
    body.put("correlationId", context.correlationId());
    body.put("timestamp", Instant.now().toString());
    String type = "application/json";
    try {
      type = negotiatedType();
    } catch (ApiFailure ignored) {
    }
    return serialize(failure.http, body, type);
  }

  private Response serialize(int status, Object body, String type) {
    try {
      String serialized =
          type.equals("application/xml")
              ? XML.writer().withRootName("response").writeValueAsString(body)
              : JSON.writeValueAsString(body);
      var out =
          Response.status(status)
              .type(type)
              .entity(serialized)
              .header("X-Correlation-ID", context.correlationId())
              .header("Cache-Control", "no-store")
              .header("X-Content-Type-Options", "nosniff");
      if (status == 401) out.header("WWW-Authenticate", "Bearer");
      if (status == 429) out.header("Retry-After", "60");
      if (status == 503) out.header("Retry-After", "1");
      if (status == 201 && context.paymentId() != null)
        out.header("Location", "/api/v1/payments/" + context.paymentId());
      return out.build();
    } catch (Exception e) {
      throw new IllegalStateException("Response serialization failed", e);
    }
  }
}
