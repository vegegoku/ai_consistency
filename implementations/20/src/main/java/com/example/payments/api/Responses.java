package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.util.*;

public final class Responses {
  private Responses() {}

  public static String negotiate(HttpHeaders headers) {
    for (MediaType m : headers.getAcceptableMediaTypes()) {
      if ("0".equals(m.getParameters().get("q"))) continue;
      if (m.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return "application/json";
      if (m.isCompatible(MediaType.APPLICATION_XML_TYPE)) return "application/xml";
    }
    throw new ApiFailure(406, "NOT_ACCEPTABLE");
  }

  public static Response render(int status, Object body, String media, UUID correlation) {
    try {
      String out =
          media.equals("application/xml")
              ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response>" + xml(body) + "</response>"
              : new ObjectMapper().writeValueAsString(body);
      Response.ResponseBuilder b =
          Response.status(status)
              .type(media)
              .entity(out)
              .header("X-Correlation-ID", correlation.toString())
              .header("Cache-Control", "no-store")
              .header("X-Content-Type-Options", "nosniff");
      if (status == 429 || status == 503) b.header("Retry-After", "2");
      return b.build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> error(ApiFailure e, UUID correlation, String paymentId) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (paymentId != null) b.put("paymentId", paymentId);
    b.put("status", "ERROR");
    b.put("code", e.code);
    b.put("message", e.getMessage());
    b.put("issues", e.issues);
    b.put("correlationId", correlation.toString());
    b.put("timestamp", Instant.now().toString());
    return b;
  }

  private static String xml(Object value) {
    if (value == null) return "";
    if (value instanceof Map<?, ?> map) {
      StringBuilder b = new StringBuilder();
      map.forEach(
          (k, v) ->
              b.append('<')
                  .append(k)
                  .append('>')
                  .append(xml(v))
                  .append("</")
                  .append(k)
                  .append('>'));
      return b.toString();
    }
    if (value instanceof Iterable<?> items) {
      StringBuilder b = new StringBuilder();
      for (Object i : items) b.append("<issue>").append(xml(i)).append("</issue>");
      return b.toString();
    }
    if (value instanceof ApiFailure.Issue i)
      return xml(Map.of("field", i.field(), "code", i.code(), "message", i.message()));
    return value
        .toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
