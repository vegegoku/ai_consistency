package com.example.payments.api;

import static com.example.payments.domain.ApiException.Issue;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;

public final class Codec {
  public static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final int limit;
  private final BigDecimal maximum;
  private final Duration age, future;
  private final Clock clock;
  private final JsonFactory json =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(4)
                  .maxStringLength(65536)
                  .maxNumberLength(100)
                  .build())
          .build();

  public Codec(int limit, String maximum, Duration age, Duration future, Clock clock) {
    this.limit = limit;
    this.maximum = new BigDecimal(maximum);
    this.age = age;
    this.future = future;
    this.clock = clock;
  }

  public PaymentCommand parse(byte[] bytes, String media) {
    if (!Set.of("application/json", "application/xml").contains(media))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    if (bytes.length > limit) throw new ApiException(413, "PAYLOAD_TOO_LARGE");
    Map<String, String> values;
    try {
      values = media.equals("application/json") ? readJson(bytes) : readXml(bytes);
    } catch (Exception e) {
      throw new ApiException(400, "MALFORMED_MESSAGE");
    }
    return validate(values);
  }

  private Map<String, String> readJson(byte[] bytes) throws IOException {
    Map<String, String> out = new HashMap<>();
    try (JsonParser p = json.createParser(bytes)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String field = p.currentName();
        if (!FIELDS.contains(field)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          out.put(field, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(field.equals("amount") && t.isNumeric()))
          throw new IOException();
        out.put(field, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return out;
  }

  private Map<String, String> readXml(byte[] bytes) throws XMLStreamException {
    var f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    var reader = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    Map<String, String> out = new HashMap<>();
    int depth = 0;
    String field = null;
    StringBuilder value = new StringBuilder();
    boolean root = false;
    try {
      while (reader.hasNext()) {
        int event = reader.next();
        switch (event) {
          case XMLStreamConstants.DTD,
                  XMLStreamConstants.ENTITY_REFERENCE,
                  XMLStreamConstants.PROCESSING_INSTRUCTION ->
              throw new XMLStreamException();
          case XMLStreamConstants.START_ELEMENT -> {
            depth++;
            if (depth > 2
                || reader.getAttributeCount() != 0
                || reader.getNamespaceCount() != 0
                || (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty()))
              throw new XMLStreamException();
            if (depth == 1) {
              if (root || !reader.getLocalName().equals("payment")) throw new XMLStreamException();
              root = true;
            } else {
              field = reader.getLocalName();
              if (!FIELDS.contains(field) || out.containsKey(field)) throw new XMLStreamException();
              value.setLength(0);
            }
          }
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
            if (depth == 2) value.append(reader.getText());
            else if (!reader.isWhiteSpace()) throw new XMLStreamException();
          }
          case XMLStreamConstants.END_ELEMENT -> {
            if (depth == 2) out.put(field, value.toString());
            depth--;
          }
          default -> {}
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return out;
    } finally {
      reader.close();
    }
  }

  private static boolean validText(String text) {
    return text.codePoints()
        .allMatch(
            c ->
                c == 9
                    || c == 10
                    || c == 13
                    || c >= 32 && c <= 0xD7FF
                    || c >= 0xE000 && c <= 0xFFFD
                    || c >= 0x10000 && c <= 0x10FFFF);
  }

  private PaymentCommand validate(Map<String, String> v) {
    List<Issue> issues = new ArrayList<>();
    String id = v.get("paymentId");
    if (id == null
        || !id.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      issues.add(new Issue("paymentId", "INVALID_UUID", "A UUID is required."));
      id = null;
    }
    for (String field : List.of("fromAccount", "toAccount")) {
      String x = v.get(field);
      if (x == null
          || x.isBlank()
          || x.length() > 100
          || x.codePoints().anyMatch(Character::isISOControl)
          || !validText(x))
        issues.add(
            new Issue(
                field,
                "INVALID_ACCOUNT",
                "A nonblank account identifier of at most 100 characters is required."));
    }
    if (v.get("fromAccount") != null && v.get("fromAccount").equals(v.get("toAccount")))
      issues.add(new Issue("toAccount", "SAME_ACCOUNT", "Accounts must differ."));
    BigDecimal amount = null;
    try {
      String raw = v.get("amount");
      if (raw == null
          || !raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(raw);
      if (amount.scale() > 3
          || amount.signum() <= 0
          || amount.compareTo(maximum) > 0
          || amount.precision() - amount.scale() > 16) throw new IllegalArgumentException();
      amount = amount.setScale(3, RoundingMode.UNNECESSARY);
    } catch (Exception e) {
      issues.add(
          new Issue(
              "amount",
              "INVALID_AMOUNT",
              "Amount must be positive, have at most three fractional digits and satisfy the"
                  + " configured maximum."));
    }
    Instant date = null;
    try {
      date = OffsetDateTime.parse(v.get("date")).toInstant();
    } catch (Exception e) {
      issues.add(
          new Issue("date", "INVALID_DATE", "An offset-aware ISO 8601 timestamp is required."));
    }
    String purpose = v.get("purpose");
    if (purpose != null) purpose = purpose.strip();
    if (purpose == null || purpose.isBlank() || purpose.length() > 255 || !validText(purpose))
      issues.add(
          new Issue(
              "purpose", "INVALID_PURPOSE", "Purpose is required and limited to 255 characters."));
    String notes = v.get("notes");
    if (notes != null) notes = notes.strip();
    if (notes != null && (notes.length() > 1000 || !validText(notes)))
      issues.add(new Issue("notes", "INVALID_NOTES", "Notes are limited to 1000 characters."));
    if (!issues.isEmpty()) {
      if (date != null && outsideWindow(date))
        issues.add(
            new Issue(
                "date",
                "DATE_OUT_OF_RANGE",
                "Instruction timestamp is outside the allowed window."));
      throw new ApiException(400, "VALIDATION_FAILED", id, issues);
    }
    return new PaymentCommand(
        id,
        v.get("fromAccount"),
        v.get("toAccount"),
        amount,
        date,
        purpose,
        notes == null ? "" : notes);
  }

  private boolean outsideWindow(Instant date) {
    Instant now = clock.instant();
    return date.isBefore(now.minus(age)) || date.isAfter(now.plus(future));
  }

  public void validateNewDate(PaymentCommand command) {
    if (outsideWindow(command.date()))
      throw new ApiException(
          400,
          "VALIDATION_FAILED",
          command.paymentId(),
          List.of(
              new Issue(
                  "date",
                  "DATE_OUT_OF_RANGE",
                  "Instruction timestamp is outside the allowed window.")));
  }

  public byte[] write(Object object, boolean xml) {
    try {
      if (!xml) return JSON.writeValueAsBytes(object);
      JsonNode tree = JSON.valueToTree(object);
      var bytes = new ByteArrayOutputStream();
      var w = XMLOutputFactory.newFactory().createXMLStreamWriter(bytes, "UTF-8");
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("response");
      writeNode(w, tree);
      w.writeEndElement();
      w.writeEndDocument();
      w.close();
      return bytes.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void writeNode(XMLStreamWriter w, JsonNode node) throws XMLStreamException {
    if (node.isObject()) {
      var it = node.fields();
      while (it.hasNext()) {
        var e = it.next();
        w.writeStartElement(e.getKey());
        writeNode(w, e.getValue());
        w.writeEndElement();
      }
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        w.writeStartElement("issue");
        writeNode(w, item);
        w.writeEndElement();
      }
    } else if (!node.isNull()) w.writeCharacters(node.asText());
  }
}
