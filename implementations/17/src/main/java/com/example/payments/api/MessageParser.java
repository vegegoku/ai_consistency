package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessageParser {
  private final int maxBytes;
  private final BigDecimal maximum;
  private final long future, age;
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final ObjectMapper json =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(8)
                      .maxStringLength(65536)
                      .maxNumberLength(64)
                      .build())
              .build());

  public MessageParser(
      @Value("${payments.max-body:65536}") int maxBytes,
      @Value("${payments.max-amount:10000.000}") String maximum,
      @Value("${payments.future-seconds:300}") long future,
      @Value("${payments.max-age-seconds:2592000}") long age) {
    this.maxBytes = maxBytes;
    this.maximum = new BigDecimal(maximum);
    this.future = future;
    this.age = age;
  }

  public PaymentCommand parse(byte[] body, String media) {
    if (body.length == 0 || body.length > maxBytes)
      throw new PaymentException(400, "MALFORMED_MESSAGE");
    Map<String, String> fields;
    try {
      fields = media.equals("application/json") ? readJson(body) : readXml(body);
    } catch (Exception e) {
      throw new PaymentException(400, "MALFORMED_MESSAGE");
    }
    var issues = new ArrayList<PaymentException.Issue>();
    String id = fields.get("paymentId");
    try {
      if (id == null || !UUID.fromString(id).toString().equals(id))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(issues, "paymentId", "A canonical UUID is required.");
      id = null;
    }
    for (String f : List.of("fromAccount", "toAccount", "purpose"))
      if (fields.get(f) == null || fields.get(f).isBlank())
        issue(issues, f, "A nonblank value is required.");
    for (String f : List.of("fromAccount", "toAccount"))
      if (fields.get(f) != null && fields.get(f).length() > 128)
        issue(issues, f, "Maximum account identifier length is 128.");
    String purpose = fields.getOrDefault("purpose", "").strip(),
        notes = fields.getOrDefault("notes", "").strip();
    if (purpose.length() > 255) issue(issues, "purpose", "Maximum length is 255.");
    if (notes.length() > 1000) issue(issues, "notes", "Maximum length is 1000.");
    BigDecimal amount = null;
    try {
      String raw = fields.get("amount");
      if (raw == null || !raw.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,4})?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(raw);
      if (amount.signum() <= 0 || amount.scale() > 3 || amount.compareTo(maximum) > 0)
        throw new IllegalArgumentException();
      amount = amount.setScale(3);
    } catch (Exception e) {
      issue(
          issues,
          "amount",
          "A positive decimal with at most 3 fractional digits within the configured maximum is"
              + " required.");
    }
    Instant date = null;
    try {
      date = OffsetDateTime.parse(fields.get("date")).toInstant();
      Instant now = Instant.now();
      if (date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(age)))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(issues, "date", "An offset timestamp within the allowed time window is required.");
    }
    if (fields.get("fromAccount") != null
        && fields.get("fromAccount").equals(fields.get("toAccount")))
      issue(issues, "toAccount", "Accounts must differ.");
    if (!issues.isEmpty()) throw new PaymentException(400, "VALIDATION_FAILED", issues, id);
    return new PaymentCommand(
        id, fields.get("fromAccount"), fields.get("toAccount"), amount, date, purpose, notes);
  }

  private void issue(List<PaymentException.Issue> list, String field, String message) {
    list.add(new PaymentException.Issue(field, "INVALID_VALUE", message));
  }

  private Map<String, String> readJson(byte[] body) throws IOException {
    try (JsonParser parser = json.createParser(body)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      var map = new HashMap<String, String>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String field = parser.currentName();
        if (!FIELDS.contains(field)) throw new IOException();
        var token = parser.nextToken();
        if (token != JsonToken.VALUE_STRING
            && !(field.equals("amount")
                && (token == JsonToken.VALUE_NUMBER_FLOAT || token == JsonToken.VALUE_NUMBER_INT)))
          throw new IOException();
        map.put(field, parser.getText());
      }
      if (parser.nextToken() != null) throw new IOException();
      return map;
    }
  }

  private Map<String, String> readXml(byte[] body) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    factory.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources disabled");
        });
    XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(body));
    var map = new HashMap<String, String>();
    int depth = 0;
    String field = null;
    StringBuilder value = null;
    boolean root = false;
    try {
      while (r.hasNext()) {
        int e = r.next();
        if (e == XMLStreamConstants.DTD
            || e == XMLStreamConstants.ENTITY_REFERENCE
            || e == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (e == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else if (depth == 2) {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || map.containsKey(field)) throw new XMLStreamException();
            value = new StringBuilder();
          } else throw new XMLStreamException();
        } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (e == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) map.put(field, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return map;
    } finally {
      r.close();
    }
  }
}
