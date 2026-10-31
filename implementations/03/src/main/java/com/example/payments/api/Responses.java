package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class Responses {
  final ObjectMapper json =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
  final XmlMapper xml = new XmlMapper();

  public String media(String accept) {
    if (accept == null || accept.isBlank()) return "application/json";
    String chosen = null;
    java.math.BigDecimal best = java.math.BigDecimal.ZERO;
    try {
      for (String part : accept.split(",")) {
        String[] sections = part.trim().split(";");
        String type = sections[0].trim();
        java.math.BigDecimal quality = java.math.BigDecimal.ONE;
        for (int i = 1; i < sections.length; i++) {
          String p = sections[i].trim();
          if (p.startsWith("q=")) quality = new java.math.BigDecimal(p.substring(2));
        }
        if (quality.signum() < 0 || quality.compareTo(java.math.BigDecimal.ONE) > 0)
          throw new IllegalArgumentException();
        String supported =
            switch (type) {
              case "application/xml" -> "application/xml";
              case "application/json", "application/*", "*/*" -> "application/json";
              default -> null;
            };
        if (supported != null && quality.compareTo(best) > 0) {
          best = quality;
          chosen = supported;
        }
      }
    } catch (Exception e) {
      throw new ApiFailure(406, "NOT_ACCEPTABLE");
    }
    if (chosen == null) throw new ApiFailure(406, "NOT_ACCEPTABLE");
    return chosen;
  }

  public Response send(int status, Object body, String accept, String correlation) {
    String type = media(accept);
    try {
      return Response.status(status)
          .type(type)
          .header("X-Correlation-ID", correlation)
          .header("Cache-Control", "no-store")
          .entity(
              type.equals("application/xml")
                  ? xml.writer().withRootName("response").writeValueAsString(body)
                  : json.writeValueAsString(body))
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> error(ApiFailure e, String correlation) {
    var out = new LinkedHashMap<String, Object>();
    if (e.paymentId != null) out.put("paymentId", e.paymentId);
    out.put("status", "ERROR");
    out.put("code", e.code);
    out.put("message", "The request could not be completed.");
    out.put("issues", e.issues);
    out.put("correlationId", correlation);
    out.put("timestamp", Instant.now().toString());
    return out;
  }
}
