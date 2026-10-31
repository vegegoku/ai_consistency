package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessageParser {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final BigDecimal maximum;
  private final Duration age, future;

  public MessageParser(
      @Value("${payments.maximum:10000.000}") BigDecimal max,
      @Value("${payments.max-age:P30D}") Duration age,
      @Value("${payments.future-skew:PT5M}") Duration future) {
    this.maximum = max;
    this.age = age;
    this.future = future;
  }

  public Command parse(byte[] body, boolean xml) {
    Map<String, String> m;
    try {
      m = xml ? xml(body) : json(body);
    } catch (Exception e) {
      throw new ApiException(400, "MALFORMED_MESSAGE");
    }
    return validate(m);
  }

  private Map<String, String> json(byte[] b) throws IOException {
    var f =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(4)
                    .maxStringLength(65536)
                    .maxNumberLength(100)
                    .build())
            .build();
    Map<String, String> m = new HashMap<>();
    try (var p = f.createParser(b)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String key = p.currentName();
        if (!FIELDS.contains(key)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          m.put(key, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(key.equals("amount") && t.isNumeric()))
          throw new IOException();
        m.put(key, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return m;
  }

  private Map<String, String> xml(byte[] b) throws XMLStreamException {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, c, d, e) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(b));
    Map<String, String> m = new HashMap<>();
    int depth = 0;
    String key = null;
    StringBuilder value = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int t = r.next();
        if (t == XMLStreamConstants.DTD
            || t == XMLStreamConstants.ENTITY_REFERENCE
            || t == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (t == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (depth > 2
              || r.getAttributeCount() > 0
              || !Objects.toString(r.getNamespaceURI(), "").isEmpty())
            throw new XMLStreamException();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else {
            key = r.getLocalName();
            if (!FIELDS.contains(key) || m.containsKey(key)) throw new XMLStreamException();
            value.setLength(0);
          }
        } else if (t == XMLStreamConstants.CHARACTERS || t == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (t == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) m.put(key, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return m;
    } finally {
      r.close();
    }
  }

  private Command validate(Map<String, String> m) {
    List<ApiException.Issue> errors = new ArrayList<>();
    for (String k : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (m.get(k) == null || m.get(k).isBlank()) issue(errors, k, "REQUIRED");
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    String rawId = m.get("paymentId");
    if (rawId != null)
      try {
        id = UUID.fromString(rawId);
        if (!id.toString().equals(rawId)) throw new IllegalArgumentException();
      } catch (Exception e) {
        id = null;
        issue(errors, "paymentId", "INVALID_UUID");
      }
    String amt = m.get("amount");
    if (amt != null)
      try {
        if (!amt.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
        amount = new BigDecimal(amt);
        if (amount.signum() <= 0) issue(errors, "amount", "POSITIVE_REQUIRED");
        if (amount.scale() > 3) issue(errors, "amount", "EXCESS_PRECISION");
        if (amount.compareTo(maximum) > 0) issue(errors, "amount", "PAYMENT_LIMIT_EXCEEDED");
        if (amount.precision() > 19) issue(errors, "amount", "OUT_OF_RANGE");
      } catch (NumberFormatException e) {
        issue(errors, "amount", "INVALID_AMOUNT");
      }
    String ds = m.get("date");
    if (ds != null)
      try {
        date = OffsetDateTime.parse(ds).toInstant();
        Instant now = Instant.now();
        if (date.isBefore(now.minus(age)) || date.isAfter(now.plus(future)))
          issue(errors, "date", "OUT_OF_RANGE");
      } catch (Exception e) {
        issue(errors, "date", "INVALID_TIMESTAMP");
      }
    for (String k : List.of("fromAccount", "toAccount"))
      if (m.get(k) != null && (m.get(k).length() > 100 || !m.get(k).equals(m.get(k).strip())))
        issue(errors, k, "INVALID_ACCOUNT_ID");
    if (Objects.equals(m.get("fromAccount"), m.get("toAccount")) && m.get("fromAccount") != null)
      issue(errors, "toAccount", "SAME_ACCOUNT");
    String purpose = Objects.toString(m.get("purpose"), "").strip(),
        notes = Objects.toString(m.get("notes"), "").strip();
    if (purpose.length() > 255) issue(errors, "purpose", "TOO_LONG");
    if (notes.length() > 1000) issue(errors, "notes", "TOO_LONG");
    if (!errors.isEmpty())
      throw new ApiException(400, "VALIDATION_FAILED", id == null ? null : id.toString(), errors);
    return new Command(
        id,
        m.get("fromAccount"),
        m.get("toAccount"),
        amount.setScale(3, RoundingMode.UNNECESSARY),
        date,
        purpose,
        notes);
  }

  private void issue(List<ApiException.Issue> e, String field, String code) {
    e.add(new ApiException.Issue(field, code, "Invalid " + field + " value."));
  }
}
