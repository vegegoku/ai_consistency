package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.*;
import java.io.StringWriter;
import java.util.*;
import javax.xml.stream.*;

public final class ResponseCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ResponseCodec() {}

  public static String errorMedia(HttpHeaders headers) {
    try {
      return negotiate(headers);
    } catch (Exception ignored) {
      return "application/json";
    }
  }

  public static String negotiate(HttpHeaders headers) {
    for (MediaType type : headers.getAcceptableMediaTypes()) {
      String quality = type.getParameters().get("q");
      if (quality != null && new java.math.BigDecimal(quality).signum() == 0) continue;
      if (type.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return "application/json";
      if (type.isCompatible(MediaType.APPLICATION_XML_TYPE)) return "application/xml";
    }
    throw new PaymentException(406, "NOT_ACCEPTABLE");
  }

  public static Response.ResponseBuilder response(
      int status, Map<String, Object> body, String media) {
    try {
      return Response.status(status)
          .type(media)
          .entity(media.equals("application/xml") ? xml(body) : JSON.writeValueAsString(body));
    } catch (Exception e) {
      throw new IllegalStateException("Response encoding failed", e);
    }
  }

  private static String xml(Map<String, Object> body) throws Exception {
    var text = new StringWriter();
    var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
    writer.writeStartDocument("UTF-8", "1.0");
    writer.writeStartElement(body.containsKey("code") ? "error" : "receipt");
    for (var entry : body.entrySet()) element(writer, entry.getKey(), entry.getValue());
    writer.writeEndElement();
    writer.writeEndDocument();
    writer.close();
    return text.toString();
  }

  private static void element(XMLStreamWriter w, String name, Object value) throws Exception {
    w.writeStartElement(name);
    if (value instanceof Collection<?> values) {
      for (Object v : values) element(w, "issue", v);
    } else if (value instanceof Map<?, ?> values) {
      for (var e : values.entrySet()) element(w, e.getKey().toString(), e.getValue());
    } else if (value != null && value.getClass().isRecord()) {
      elementContents(w, JSON.convertValue(value, Map.class));
    } else if (value != null) w.writeCharacters(value.toString());
    w.writeEndElement();
  }

  private static void elementContents(XMLStreamWriter w, Map<?, ?> values) throws Exception {
    for (var e : values.entrySet()) element(w, e.getKey().toString(), e.getValue());
  }
}
