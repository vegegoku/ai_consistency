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
  private final int maxBytes;
  private final BigDecimal max;
  private final long futureSeconds, ageSeconds;
  private final JsonFactory json =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(8)
                  .maxNumberLength(64)
                  .maxStringLength(4096)
                  .build())
          .build();

  public MessageParser(
      @Value("${payments.max-body-bytes:65536}") int size,
      @Value("${payments.max-amount:10000.000}") String max,
      @Value("${payments.future-seconds:300}") long future,
      @Value("${payments.max-age-seconds:2592000}") long age) {
    this.maxBytes = size;
    this.max = new BigDecimal(max);
    this.futureSeconds = future;
    this.ageSeconds = age;
  }

  public PaymentCommand parse(byte[] body, String type) {
    if (body.length == 0) throw new ApiFailure(400, "MALFORMED_MESSAGE", "A document is required.");
    if (body.length > maxBytes)
      throw new ApiFailure(413, "PAYLOAD_TOO_LARGE", "Request exceeds the size limit.");
    Map<String, String> values;
    try {
      values =
          type.equals("application/json")
              ? readJson(body)
              : type.equals("application/xml") ? readXml(body) : null;
    } catch (Exception e) {
      throw new ApiFailure(
          400, "MALFORMED_MESSAGE", "Document is malformed or contains ambiguous fields.");
    }
    if (values == null)
      throw new ApiFailure(
          415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json or application/xml.");
    return validate(values);
  }

  private Map<String, String> readJson(byte[] body) throws IOException {
    Map<String, String> out = new HashMap<>();
    try (JsonParser p = json.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          out.put(name, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(name.equals("amount") && t.isNumeric()))
          throw new IOException();
        out.put(name, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return out;
  }

  private Map<String, String> readXml(byte[] body) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External access prohibited");
        });
    XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(body));
    Map<String, String> out = new HashMap<>();
    int depth = 0;
    String field = null;
    StringBuilder content = new StringBuilder();
    boolean root = false, closed = false;
    try {
      while (r.hasNext()) {
        int t = r.next();
        if (t == XMLStreamConstants.DTD
            || t == XMLStreamConstants.ENTITY_REFERENCE
            || t == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (t == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (closed
              || depth > 2
              || r.getAttributeCount() > 0
              || r.getNamespaceCount() > 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          String name = r.getLocalName();
          if (depth == 1) {
            if (root || !name.equals("payment")) throw new XMLStreamException();
            root = true;
          } else {
            if (!FIELDS.contains(name) || out.containsKey(name)) throw new XMLStreamException();
            field = name;
            content.setLength(0);
          }
        } else if (t == XMLStreamConstants.CHARACTERS || t == XMLStreamConstants.CDATA) {
          if (depth == 2) content.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (t == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) out.put(field, content.toString());
          if (depth == 1) closed = true;
          depth--;
        }
      }
    } finally {
      r.close();
    }
    if (!root || !closed || depth != 0) throw new XMLStreamException();
    return out;
  }

  private PaymentCommand validate(Map<String, String> v) {
    List<Issue> errors = new ArrayList<>();
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (v.get(f) == null || v.get(f).isBlank())
        errors.add(new Issue(f, "REQUIRED", "A value is required."));
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    if (present(v, "paymentId")) {
      try {
        String s = v.get("paymentId");
        if (!s.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new IllegalArgumentException();
        id = UUID.fromString(s);
      } catch (Exception e) {
        errors.add(new Issue("paymentId", "INVALID_UUID", "Must be a UUID."));
      }
    }
    for (String f : List.of("fromAccount", "toAccount"))
      if (present(v, f) && (v.get(f).length() > 100 || !v.get(f).matches("[A-Za-z0-9_-]+")))
        errors.add(
            new Issue(
                f, "INVALID_ACCOUNT_ID", "Use 1 to 100 letters, digits, underscore or hyphen."));
    if (present(v, "amount")) {
      try {
        String a = v.get("amount");
        if (!a.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new NumberFormatException();
        amount = new BigDecimal(a);
        if (amount.signum() <= 0 || amount.scale() > 3 || amount.compareTo(max) > 0)
          errors.add(
              new Issue(
                  "amount",
                  "INVALID_AMOUNT",
                  "Must be positive, have at most 3 decimal places and not exceed "
                      + max.toPlainString()
                      + " JOD."));
        else amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        errors.add(
            new Issue(
                "amount", "INVALID_AMOUNT", "Must be a decimal amount without exponent notation."));
      }
    }
    if (present(v, "date")) {
      try {
        date = OffsetDateTime.parse(v.get("date")).toInstant();
        Instant now = Instant.now();
        if (date.isAfter(now.plusSeconds(futureSeconds))
            || date.isBefore(now.minusSeconds(ageSeconds)))
          errors.add(
              new Issue(
                  "date",
                  "DATE_OUT_OF_RANGE",
                  "Instruction timestamp is outside the permitted window."));
      } catch (Exception e) {
        errors.add(
            new Issue("date", "INVALID_DATE", "An ISO 8601 timestamp with an offset is required."));
      }
    }
    String purpose = v.get("purpose") == null ? null : v.get("purpose").trim(),
        notes = v.get("notes") == null ? null : v.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      errors.add(new Issue("purpose", "TOO_LONG", "Maximum length is 255."));
    if (notes != null && notes.length() > 1000)
      errors.add(new Issue("notes", "TOO_LONG", "Maximum length is 1000."));
    if (present(v, "fromAccount") && v.get("fromAccount").equals(v.get("toAccount")))
      errors.add(new Issue("toAccount", "SAME_ACCOUNT", "Accounts must differ."));
    if (!errors.isEmpty())
      throw new ApiFailure(
          400,
          "VALIDATION_FAILED",
          "Input validation failed.",
          id == null ? null : id.toString(),
          errors);
    return new PaymentCommand(
        id, v.get("fromAccount"), v.get("toAccount"), amount, date, purpose, notes);
  }

  private boolean present(Map<String, String> v, String f) {
    return v.get(f) != null && !v.get(f).isBlank();
  }
}
