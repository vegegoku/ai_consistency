package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;

public class PaymentParser {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final BigDecimal maximum;
  private final Duration future, age;

  public PaymentParser() {
    this(new BigDecimal("10000.000"), Duration.ofMinutes(5), Duration.ofDays(30));
  }

  public PaymentParser(BigDecimal maximum, Duration future, Duration age) {
    this.maximum = maximum;
    this.future = future;
    this.age = age;
  }

  public PaymentCommand parse(byte[] body, boolean xml) {
    Map<String, String> m;
    try {
      m = xml ? xml(body) : json(body);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "MALFORMED_MESSAGE");
    }
    return validate(m);
  }

  private Map<String, String> json(byte[] bytes) throws IOException {
    var factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxStringLength(65536)
                    .maxNumberLength(100)
                    .build())
            .build();
    try (var p = factory.createParser(bytes)) {
      var map = new LinkedHashMap<String, String>();
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String f = p.currentName();
        if (!FIELDS.contains(f)) throw new IOException();
        var t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          map.put(f, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(f.equals("amount") && t.isNumeric()))
          throw new IOException();
        map.put(f, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
      return map;
    }
  }

  private Map<String, String> xml(byte[] bytes) throws XMLStreamException {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External access prohibited");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    var m = new LinkedHashMap<String, String>();
    int depth = 0;
    String key = null;
    StringBuilder text = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int t = r.next();
        if (t == XMLStreamConstants.DTD
            || t == XMLStreamConstants.ENTITY_REFERENCE
            || t == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (t == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() > 0
              || r.getNamespaceCount() > 0
              || !Objects.toString(r.getNamespaceURI(), "").isEmpty())
            throw new XMLStreamException();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else if (depth == 2) {
            key = r.getLocalName();
            if (!FIELDS.contains(key) || m.containsKey(key)) throw new XMLStreamException();
            text.setLength(0);
          } else throw new XMLStreamException();
        } else if (t == XMLStreamConstants.CHARACTERS || t == XMLStreamConstants.CDATA) {
          if (depth == 2) text.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (t == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) m.put(key, text.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return m;
    } finally {
      r.close();
    }
  }

  private PaymentCommand validate(Map<String, String> m) {
    var issues = new ArrayList<ApiException.Issue>();
    for (String k : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (m.get(k) == null || m.get(k).isBlank())
        issues.add(new ApiException.Issue(k, "REQUIRED", "A nonblank value is required."));
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    if (nonblank(m, "paymentId"))
      try {
        String s = m.get("paymentId");
        id = UUID.fromString(s);
        if (!id.toString().equals(s)) throw new IllegalArgumentException();
      } catch (Exception e) {
        id = null;
        issue(issues, "paymentId", "A canonical UUID is required.");
      }
    for (String k : List.of("fromAccount", "toAccount"))
      if (m.get(k) != null && (m.get(k).length() > 100 || !m.get(k).equals(m.get(k).trim())))
        issue(issues, k, "Account identifiers must be exact and at most 100 characters.");
    if (nonblank(m, "amount"))
      try {
        String s = m.get("amount");
        if (!s.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException();
        amount = new BigDecimal(s);
        if (amount.scale() > 3 || amount.signum() <= 0 || amount.compareTo(maximum) > 0)
          throw new IllegalArgumentException();
        amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issue(
            issues,
            "amount",
            "Amount must be positive, within the maximum, with at most 3 decimals.");
      }
    if (nonblank(m, "date"))
      try {
        date = OffsetDateTime.parse(m.get("date")).toInstant();
        Instant now = Instant.now();
        if (date.isAfter(now.plus(future)) || date.isBefore(now.minus(age)))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        issue(issues, "date", "An offset timestamp within the permitted time window is required.");
      }
    if (nonblank(m, "fromAccount") && m.get("fromAccount").equals(m.get("toAccount")))
      issue(issues, "toAccount", "Accounts must differ.");
    String purpose = m.get("purpose") == null ? null : m.get("purpose").trim(),
        notes = m.get("notes") == null ? null : m.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      issue(issues, "purpose", "Maximum length is 255.");
    if (notes != null && notes.length() > 1000) issue(issues, "notes", "Maximum length is 1000.");
    for (var entry : m.entrySet())
      if (entry.getValue() != null && entry.getValue().indexOf(0) >= 0)
        issue(issues, entry.getKey(), "Null characters are not permitted.");
    if (!issues.isEmpty()) throw new ApiException(400, "VALIDATION_FAILED", issues, id);
    return new PaymentCommand(
        id, m.get("fromAccount"), m.get("toAccount"), amount, date, purpose, notes);
  }

  private boolean nonblank(Map<String, String> m, String k) {
    return m.get(k) != null && !m.get(k).isBlank();
  }

  private void issue(List<ApiException.Issue> l, String f, String msg) {
    l.add(new ApiException.Issue(f, "INVALID", msg));
  }
}
