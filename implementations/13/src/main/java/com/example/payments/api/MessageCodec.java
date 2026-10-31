package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.util.*;
import javax.xml.stream.*;

@ApplicationScoped
public class MessageCodec {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final ObjectMapper mapper =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(16)
                      .maxStringLength(65536)
                      .maxNumberLength(64)
                      .build())
              .build());

  public Map<String, String> parse(byte[] input, boolean xml) {
    try {
      return xml ? xml(input) : json(input);
    } catch (Exception e) {
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    }
  }

  private Map<String, String> json(byte[] bytes) throws Exception {
    var result = new HashMap<String, String>();
    try (var p = mapper.createParser(bytes)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String key = p.currentName();
        if (!FIELDS.contains(key) || result.containsKey(key)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          if (!key.equals("notes")) result.put(key, null);
          else result.put(key, "");
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(key.equals("amount") && t.isNumeric()))
          throw new IOException();
        result.put(key, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return result;
  }

  private Map<String, String> xml(byte[] bytes) throws Exception {
    var f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External access prohibited");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    var result = new HashMap<String, String>();
    int depth = 0;
    boolean root = false;
    String field = null;
    StringBuilder value = new StringBuilder();
    try {
      while (r.hasNext()) {
        int e = r.next();
        if (e == XMLStreamConstants.DTD
            || e == XMLStreamConstants.ENTITY_REFERENCE
            || e == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (e == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (depth > 2
              || r.getAttributeCount() > 0
              || r.getNamespaceCount() > 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || result.containsKey(field))
              throw new XMLStreamException();
            value.setLength(0);
          }
        } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (e == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) result.put(field, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return result;
    } finally {
      r.close();
    }
  }

  public String encode(Object value, boolean xml) {
    try {
      if (!xml) return mapper.writeValueAsString(value);
      var out = new StringWriter();
      var w = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("response");
      write(w, mapper.valueToTree(value));
      w.writeEndElement();
      w.close();
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void write(XMLStreamWriter w, JsonNode node) throws Exception {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        if (entry.getValue().isNull()) continue;
        w.writeStartElement(entry.getKey());
        write(w, entry.getValue());
        w.writeEndElement();
      }
    } else if (node.isArray()) {
      for (var item : node) {
        w.writeStartElement("issue");
        write(w, item);
        w.writeEndElement();
      }
    } else w.writeCharacters(node.asText());
  }
}
