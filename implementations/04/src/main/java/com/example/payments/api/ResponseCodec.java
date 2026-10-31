package com.example.payments.api;

import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import java.io.StringWriter;
import javax.xml.stream.*;

@ApplicationScoped
public class ResponseCodec {
  @Inject ObjectMapper mapper;

  public String negotiate(HttpHeaders headers) {
    for (var t : headers.getAcceptableMediaTypes()) {
      if (new java.math.BigDecimal(t.getParameters().getOrDefault("q", "1")).signum() == 0)
        continue;
      if (t.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return "application/json";
      if (t.isCompatible(MediaType.APPLICATION_XML_TYPE)) return "application/xml";
    }
    throw new com.example.payments.domain.ApiException(406, "NOT_ACCEPTABLE");
  }

  public Response response(Object entity, int status, String type, String correlation) {
    try {
      String body;
      if (type.equals("application/xml")) {
        StringWriter output = new StringWriter();
        var w = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
        w.writeStartDocument("UTF-8", "1.0");
        w.writeStartElement("response");
        write(w, mapper.valueToTree(entity));
        w.writeEndElement();
        w.writeEndDocument();
        w.close();
        body = output.toString();
      } else body = mapper.writeValueAsString(entity);
      var b =
          Response.status(status)
              .type(type)
              .entity(body)
              .header("X-Correlation-ID", correlation)
              .header("Cache-Control", "no-store")
              .header("X-Content-Type-Options", "nosniff");
      if (status == 429 || status == 503) b.header("Retry-After", status == 429 ? "60" : "2");
      if (status == 401) b.header("WWW-Authenticate", "Bearer");
      return b.build();
    } catch (Exception e) {
      throw new IllegalStateException("Response encoding failed", e);
    }
  }

  private void write(XMLStreamWriter w, JsonNode n) throws XMLStreamException {
    if (n.isObject()) {
      var fields = n.fields();
      while (fields.hasNext()) {
        var f = fields.next();
        if (f.getValue().isNull()) continue;
        w.writeStartElement(f.getKey());
        write(w, f.getValue());
        w.writeEndElement();
      }
    } else if (n.isArray()) {
      for (var item : n) {
        w.writeStartElement("item");
        write(w, item);
        w.writeEndElement();
      }
    } else if (!n.isNull()) w.writeCharacters(n.asText());
  }
}
