package com.example.payments.api;

import com.example.payments.domain.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class Responder {
  private final ObjectMapper json;

  public Responder(ObjectMapper json) {
    this.json = json;
  }

  public ResponseEntity<byte[]> response(int status, Object body, HttpServletRequest request) {
    boolean xml = xml(request.getHeader("Accept"));
    try {
      byte[] bytes;
      if (xml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
        w.writeStartDocument("UTF-8", "1.0");
        element(w, status >= 400 ? "error" : "receipt", body);
        w.writeEndDocument();
        w.close();
        bytes = out.toByteArray();
      } else bytes = json.writeValueAsBytes(body);
      ResponseEntity.BodyBuilder response =
          ResponseEntity.status(status)
              .header("Vary", "Accept")
              .contentType(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON);
      if (status == 503 || status == 429) response.header("Retry-After", "2");
      if (status == 201 && body instanceof Map<?, ?> map)
        response.header("Location", "/api/v1/payments/" + map.get("paymentId"));
      return response.body(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void checkAccept(HttpServletRequest request) {
    xml(request.getHeader("Accept"));
  }

  public ResponseEntity<byte[]> jsonError(ApiError e, HttpServletRequest request) {
    try {
      return ResponseEntity.status(e.http)
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsBytes(e.body(correlation(request))));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private boolean xml(String accept) {
    if (accept == null || accept.isBlank()) return false;
    List<MediaType> types;
    try {
      types = new ArrayList<>(MediaType.parseMediaTypes(accept));
    } catch (Exception e) {
      throw new ApiError(406, "NOT_ACCEPTABLE");
    }
    double jsonQuality = quality(types, MediaType.APPLICATION_JSON);
    double xmlQuality = quality(types, MediaType.APPLICATION_XML);
    if (jsonQuality <= 0 && xmlQuality <= 0) throw new ApiError(406, "NOT_ACCEPTABLE");
    return xmlQuality > jsonQuality;
  }

  private double quality(List<MediaType> requested, MediaType offered) {
    int specificity = -1;
    double quality = 0;
    for (MediaType type : requested) {
      if (!type.includes(offered)) continue;
      int score = type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2;
      if (score > specificity) {
        specificity = score;
        quality = type.getQualityValue();
      }
    }
    return quality;
  }

  private void element(XMLStreamWriter w, String name, Object value) throws XMLStreamException {
    w.writeStartElement(name);
    if (value instanceof Map<?, ?> m) {
      for (var e : m.entrySet()) element(w, e.getKey().toString(), e.getValue());
    } else if (value instanceof Iterable<?> list) {
      for (Object item : list) element(w, "issue", item);
    } else if (value != null) w.writeCharacters(value.toString());
    w.writeEndElement();
  }

  public void error(HttpServletRequest request, HttpServletResponse response, int http, String code)
      throws IOException {
    Map<String, Object> b = new ApiError(http, code).body(correlation(request));
    ResponseEntity<byte[]> entity;
    try {
      entity = response(http, b, request);
    } catch (ApiError e) {
      entity =
          ResponseEntity.status(406)
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  json.writeValueAsBytes(
                      new ApiError(406, "NOT_ACCEPTABLE").body(correlation(request))));
    }
    response.setStatus(entity.getStatusCode().value());
    entity.getHeaders().forEach((k, v) -> response.setHeader(k, String.join(",", v)));
    response.getOutputStream().write(entity.getBody());
  }

  public static String correlation(HttpServletRequest request) {
    Object c = request.getAttribute("correlationId");
    return c == null ? UUID.randomUUID().toString() : c.toString();
  }
}
