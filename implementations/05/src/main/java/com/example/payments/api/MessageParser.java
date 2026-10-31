package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;

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
                          .maxStringLength(65536)
                          .maxNumberLength(100)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final BigDecimal maximum;
  private final Duration future, old;

  public MessageParser() {
    this(new BigDecimal("10000.000"), Duration.ofMinutes(5), Duration.ofDays(30));
  }

  public MessageParser(BigDecimal maximum, Duration future, Duration old) {
    this.maximum = maximum;
    this.future = future;
    this.old = old;
  }

  public PaymentCommand parse(byte[] bytes, boolean xml) {
    if (bytes.length == 0) throw new ApiException(400, "MALFORMED_MESSAGE");
    Map<String, String> v;
    try {
      v = xml ? xml(bytes) : json(bytes);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "MALFORMED_MESSAGE");
    }
    List<Issue> issues = new ArrayList<>();
    for (String name : FIELDS)
      if (!name.equals("notes") && (v.get(name) == null || v.get(name).isBlank()))
        issues.add(new Issue(name, "REQUIRED", "A nonblank value is required."));
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    if (v.get("paymentId") != null)
      try {
        id = UUID.fromString(v.get("paymentId"));
        if (!id.toString().equalsIgnoreCase(v.get("paymentId")))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        issues.add(issue("paymentId", "A canonical UUID is required."));
      }
    if (v.get("amount") != null)
      try {
        String raw = v.get("amount");
        if (!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
          throw new NumberFormatException();
        amount = new BigDecimal(raw);
        if (amount.scale() > 3 || amount.signum() <= 0 || amount.compareTo(maximum) > 0)
          throw new NumberFormatException();
        amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issues.add(
            issue(
                "amount",
                "Amount must be positive, have at most 3 fractional digits and not exceed the"
                    + " maximum."));
      }
    if (v.get("date") != null)
      try {
        date = OffsetDateTime.parse(v.get("date")).toInstant();
        Instant now = Instant.now();
        if (date.isAfter(now.plus(future)) || date.isBefore(now.minus(old)))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        issues.add(
            issue("date", "A valid offset timestamp within the permitted window is required."));
      }
    for (String account : List.of("fromAccount", "toAccount"))
      if (v.get(account) != null
          && (v.get(account).length() > 128 || !v.get(account).equals(v.get(account).trim())))
        issues.add(
            issue(
                account,
                "Account identifier must not contain surrounding whitespace and must be at most 128"
                    + " characters."));
    if (v.get("fromAccount") != null && v.get("fromAccount").equals(v.get("toAccount")))
      issues.add(issue("toAccount", "Source and destination must differ."));
    String purpose = v.get("purpose") == null ? null : v.get("purpose").trim(),
        notes = v.get("notes") == null ? "" : v.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      issues.add(issue("purpose", "Maximum length is 255."));
    if (notes.length() > 1000) issues.add(issue("notes", "Maximum length is 1000."));
    if (!issues.isEmpty()) throw new ApiException(400, "VALIDATION_FAILED", issues, id);
    return new PaymentCommand(
        id, v.get("fromAccount"), v.get("toAccount"), amount, date, purpose, notes);
  }

  private Issue issue(String f, String message) {
    return new Issue(f, "INVALID_VALUE", message);
  }

  private Map<String, String> json(byte[] bytes) throws IOException {
    Map<String, String> v = new HashMap<>();
    try (var p = json.getFactory().createParser(bytes)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new ApiException(400, "MALFORMED_MESSAGE");
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME)
          throw new ApiException(400, "MALFORMED_MESSAGE");
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw new ApiException(400, "MALFORMED_MESSAGE");
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          v.put(name, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING
            && !(name.equals("amount")
                && (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT)))
          throw new ApiException(400, "MALFORMED_MESSAGE");
        v.put(name, p.getText());
      }
      if (p.nextToken() != null) throw new ApiException(400, "MALFORMED_MESSAGE");
    }
    return v;
  }

  private Map<String, String> xml(byte[] bytes) throws XMLStreamException {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    Map<String, String> v = new HashMap<>();
    int depth = 0;
    String key = null;
    StringBuilder value = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException("Prohibited construct");
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException("Attributes and namespaces prohibited");
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException("Root");
            root = true;
          } else if (depth == 2) {
            key = r.getLocalName();
            if (!FIELDS.contains(key) || v.containsKey(key))
              throw new XMLStreamException("Unknown or duplicate field");
            value.setLength(0);
          } else throw new XMLStreamException("Nested values");
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException("Unexpected text");
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) v.put(key, value.toString());
          depth--;
        }
      }
      if (!root) throw new XMLStreamException("Missing root");
    } finally {
      r.close();
    }
    return v;
  }
}
