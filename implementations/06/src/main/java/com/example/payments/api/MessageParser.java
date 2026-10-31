package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringReader;
import java.util.*;
import javax.xml.stream.*;

@ApplicationScoped
public class MessageParser {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final ObjectMapper json =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxNumberLength(64)
                          .maxStringLength(65536)
                          .build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  public Map<String, String> parse(String body, boolean xml) {
    if (body == null || body.isBlank()) throw new PaymentException(400, "MALFORMED_MESSAGE");
    try {
      return xml ? xml(body) : json(body);
    } catch (PaymentException e) {
      throw e;
    } catch (Exception e) {
      throw new PaymentException(400, "MALFORMED_MESSAGE");
    }
  }

  private Map<String, String> json(String body) throws Exception {
    var result = new HashMap<String, String>();
    try (var p = json.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new Exception();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new Exception();
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw new Exception();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          result.put(name, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING
            && !(name.equals("amount")
                && (t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_NUMBER_INT)))
          throw new Exception();
        result.put(name, p.getText());
      }
      if (p.nextToken() != null) throw new Exception();
    }
    return result;
  }

  private Map<String, String> xml(String body) throws Exception {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources forbidden");
        });
    var r = f.createXMLStreamReader(new StringReader(body));
    var result = new HashMap<String, String>();
    int depth = 0;
    boolean root = false, closed = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD
            || event == XMLStreamConstants.ENTITY_REFERENCE
            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new Exception();
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()
              || r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0) throw new Exception();
          String name = r.getLocalName();
          if (depth == 1) {
            if (root || !name.equals("payment")) throw new Exception();
            root = true;
          } else if (depth == 2) {
            if (!FIELDS.contains(name) || result.containsKey(name)) throw new Exception();
            String text = r.getElementText();
            result.put(name, text);
            depth--;
          } else throw new Exception();
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          depth--;
          if (depth == 0) closed = true;
        } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
            && !r.isWhiteSpace()) throw new Exception();
      }
      if (!root || !closed || depth != 0) throw new Exception();
      return result;
    } finally {
      r.close();
    }
  }
}
