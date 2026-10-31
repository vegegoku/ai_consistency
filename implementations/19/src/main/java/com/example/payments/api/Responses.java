package com.example.payments.api;

import com.example.payments.domain.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class Responses {
  private final ObjectMapper json = new ObjectMapper();
  private final XmlMapper xml = new XmlMapper();

  public boolean wantsXml(HttpServletRequest r) {
    String accept = r.getHeader("Accept");
    if (accept == null || accept.isBlank()) return false;
    try {
      var types = MediaType.parseMediaTypes(accept);
      types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
      for (var type : types) {
        if (type.getQualityValue() <= 0) continue;
        if (type.isCompatibleWith(MediaType.APPLICATION_JSON)) return false;
        if (type.isCompatibleWith(MediaType.APPLICATION_XML)) return true;
      }
    } catch (Exception ignored) {
    }
    throw new ApiException(406, "NOT_ACCEPTABLE");
  }

  public Map<String, Object> error(ApiException e) {
    var b = new LinkedHashMap<String, Object>();
    if (e.paymentId != null) b.put("paymentId", e.paymentId);
    b.put("status", "ERROR");
    b.put("code", e.code);
    b.put("message", "The request could not be completed.");
    b.put("issues", e.issues);
    return b;
  }

  public ResponseEntity<byte[]> response(
      int status, Map<String, Object> body, HttpServletRequest request) {
    boolean useXml = wantsXml(request);
    return response(status, body, useXml);
  }

  public ResponseEntity<byte[]> response(int status, Map<String, Object> body, boolean useXml) {
    try {
      var b = new LinkedHashMap<>(body);
      if (status >= 400 || "REJECTED".equals(body.get("status"))) {
        b.put(
            "correlationId",
            Objects.toString(MDC.get("correlationId"), UUID.randomUUID().toString()));
        b.putIfAbsent("timestamp", Instant.now().toString());
      }
      var builder =
          ResponseEntity.status(status)
              .contentType(useXml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON);
      if (status == 401) builder.header("WWW-Authenticate", "Bearer");
      if (status == 201) builder.header("Location", "/api/v1/payments/" + body.get("paymentId"));
      if (status == 429 || status == 503) builder.header("Retry-After", "2");
      return builder.body(
          useXml
              ? xml.writer().withRootName("response").writeValueAsBytes(b)
              : json.writeValueAsBytes(b));
    } catch (Exception e) {
      throw new IllegalStateException("Response serialization failed", e);
    }
  }

  public void write(HttpServletRequest request, HttpServletResponse response, ApiException e)
      throws java.io.IOException {
    boolean useXml = false;
    try {
      useXml = wantsXml(request);
    } catch (ApiException ignored) {
    }
    var entity = response(e.http, error(e), useXml);
    response.setStatus(e.http);
    entity.getHeaders().forEach((key, values) -> values.forEach(v -> response.addHeader(key, v)));
    response.getOutputStream().write(entity.getBody());
  }
}
