package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class ResponseWriter {
  private final ObjectMapper json;
  private final XmlMapper xml = new XmlMapper();

  public ResponseWriter(ObjectMapper json) {
    this.json = json;
  }

  public String negotiate(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    if (accept == null || accept.isBlank()) return "application/json";
    try {
      var types = MediaType.parseMediaTypes(accept);
      types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
      for (var t : types) {
        if (t.getQualityValue() <= 0) continue;
        if (t.isCompatibleWith(MediaType.APPLICATION_JSON)) return "application/json";
        if (t.isCompatibleWith(MediaType.APPLICATION_XML)) return "application/xml";
      }
    } catch (IllegalArgumentException ignored) {
    }
    throw new ApiFailure(
        406, "NOT_ACCEPTABLE", "Use application/json or application/xml for responses.");
  }

  public ResponseEntity<byte[]> response(int status, Object body, HttpServletRequest request) {
    String type = negotiate(request);
    try {
      return ResponseEntity.status(status)
          .contentType(MediaType.parseMediaType(type))
          .body(bytes(body, type));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] bytes(Object body, String type) throws IOException {
    return type.equals("application/xml")
        ? xml.writer().withRootName("response").writeValueAsBytes(body)
        : json.writeValueAsBytes(body);
  }

  public Map<String, Object> error(ApiFailure e, HttpServletRequest request) {
    request.setAttribute("errorCode", e.code());
    Map<String, Object> b = new LinkedHashMap<>();
    if (e.paymentId() != null) b.put("paymentId", e.paymentId());
    b.put("status", "ERROR");
    b.put("code", e.code());
    b.put("message", e.getMessage());
    b.put("issues", e.issues());
    b.put(
        "correlationId",
        Objects.toString(request.getAttribute("correlationId"), UUID.randomUUID().toString()));
    b.put("timestamp", Instant.now().toString());
    return b;
  }

  public void writeError(
      HttpServletRequest request, HttpServletResponse response, ApiFailure failure)
      throws IOException {
    String type;
    try {
      type = negotiate(request);
    } catch (ApiFailure ignored) {
      type = "application/json";
    }
    response.setStatus(failure.http());
    response.setContentType(type);
    if (failure.http() == 503 || failure.http() == 429) response.setHeader("Retry-After", "2");
    response.getOutputStream().write(bytes(error(failure, request), type));
  }
}
