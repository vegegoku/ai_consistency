package com.example.payments.api;

import com.example.payments.application.Limits;
import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;

@ApplicationScoped
public class MessageParser {
  private final Clock clock;
  private final BigDecimal maximum;
  private final long futureSeconds, ageSeconds;
  private final int maxBytes;
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");

  @Inject
  public MessageParser(Limits l) {
    this(Clock.systemUTC(), l.maximum, l.futureSeconds, l.ageSeconds, l.bodyBytes);
  }

  public MessageParser(
      Clock clock, BigDecimal maximum, long futureSeconds, long ageSeconds, int maxBytes) {
    this.clock = clock;
    this.maximum = maximum;
    this.futureSeconds = futureSeconds;
    this.ageSeconds = ageSeconds;
    this.maxBytes = maxBytes;
  }

  public PaymentCommand parse(byte[] bytes, String media) {
    if (bytes == null || bytes.length == 0)
      throw new ApiException(400, "MALFORMED_MESSAGE", "A request body is required.");
    if (bytes.length > maxBytes)
      throw new ApiException(413, "PAYLOAD_TOO_LARGE", "The request body exceeds its limit.");
    Map<String, String> values;
    try {
      values =
          switch (media) {
            case "application/json" -> json(bytes);
            case "application/xml" -> xml(bytes);
            default ->
                throw new ApiException(
                    415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json or application/xml.");
          };
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(
          400, "MALFORMED_MESSAGE", "The document is malformed or has invalid typed values.");
    }
    return validate(values);
  }

  private Map<String, String> json(byte[] bytes) throws IOException {
    var factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxStringLength(maxBytes)
                    .maxNumberLength(64)
                    .build())
            .build();
    var mapper =
        new ObjectMapper(factory)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    Map<String, String> values = new HashMap<>();
    try (var parser = factory.createParser(bytes)) {
      JsonNode node = mapper.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) throw new IOException();
      var fields = node.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        var key = entry.getKey();
        var value = entry.getValue();
        if (!FIELDS.contains(key)) throw new IOException();
        if (value.isNull()) {
          values.put(key, null);
          continue;
        }
        if (!value.isTextual() && !(key.equals("amount") && value.isNumber()))
          throw new IOException();
        // JsonNode floating point nodes can lose decimal precision; the original numeric token is
        // read below.
        values.put(key, value.asText());
      }
    }
    // Preserve lexical BigDecimal scale and precision rather than converting through binary
    // floating point.
    try (var parser = factory.createParser(bytes)) {
      parser.nextToken();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String key = parser.currentName();
        JsonToken token = parser.nextToken();
        if (key.equals("amount") && token.isNumeric()) values.put(key, parser.getText());
        else parser.skipChildren();
      }
    }
    return values;
  }

  private Map<String, String> xml(byte[] bytes) throws XMLStreamException {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setXMLResolver(
        (publicId, systemId, base, namespace) -> {
          throw new XMLStreamException("External access disabled");
        });
    var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    var values = new HashMap<String, String>();
    String key = null;
    StringBuilder content = new StringBuilder();
    int depth = 0;
    boolean seen = false;
    try {
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD
            || event == XMLStreamConstants.ENTITY_REFERENCE
            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (reader.getAttributeCount() != 0
              || reader.getNamespaceCount() != 0
              || (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          if (depth == 1) {
            if (seen || !reader.getLocalName().equals("payment")) throw new XMLStreamException();
            seen = true;
          } else if (depth == 2) {
            key = reader.getLocalName();
            if (!FIELDS.contains(key) || values.containsKey(key)) throw new XMLStreamException();
            content.setLength(0);
          } else throw new XMLStreamException();
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) content.append(reader.getText());
          else if (!reader.isWhiteSpace()) throw new XMLStreamException();
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) values.put(key, content.toString());
          depth--;
        }
      }
    } finally {
      reader.close();
    }
    if (!seen || depth != 0) throw new XMLStreamException();
    return values;
  }

  private PaymentCommand validate(Map<String, String> v) {
    var issues = new ArrayList<Issue>();
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String field :
        List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (v.get(field) == null || v.get(field).isBlank())
        issues.add(new Issue(field, "REQUIRED", "This field is required."));
    String rawId = v.get("paymentId");
    if (rawId != null && !rawId.isBlank())
      try {
        if (!rawId.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new IllegalArgumentException();
        id = UUID.fromString(rawId);
      } catch (IllegalArgumentException e) {
        issues.add(new Issue("paymentId", "INVALID_UUID", "Use a canonical UUID."));
      }
    for (String f : List.of("fromAccount", "toAccount"))
      if (v.get(f) != null && v.get(f).length() > 128)
        issues.add(new Issue(f, "TOO_LONG", "Account identifier exceeds 128 characters."));
    if (v.get("fromAccount") != null && v.get("fromAccount").equals(v.get("toAccount")))
      issues.add(new Issue("toAccount", "SAME_ACCOUNT", "Accounts must differ."));
    String a = v.get("amount");
    if (a != null && !a.isBlank())
      try {
        if (a.length() > 64 || !a.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
          throw new NumberFormatException();
        amount = new BigDecimal(a);
        if (amount.signum() <= 0)
          issues.add(new Issue("amount", "INVALID_AMOUNT", "Amount must be positive."));
        if (amount.scale() > 3)
          issues.add(
              new Issue("amount", "EXCESS_PRECISION", "Use at most three fractional digits."));
        if (amount.compareTo(maximum) > 0)
          issues.add(
              new Issue(
                  "amount", "PAYMENT_LIMIT_EXCEEDED", "Amount exceeds the configured maximum."));
        if (amount.scale() <= 3) amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (ArithmeticException | NumberFormatException e) {
        issues.add(
            new Issue(
                "amount",
                "INVALID_AMOUNT",
                "Use an exact decimal amount without exponent notation."));
      }
    String d = v.get("date");
    if (d != null && !d.isBlank())
      try {
        date = OffsetDateTime.parse(d).toInstant();
        Instant now = clock.instant();
        if (date.isAfter(now.plusSeconds(futureSeconds))
            || date.isBefore(now.minusSeconds(ageSeconds)))
          issues.add(
              new Issue(
                  "date",
                  "DATE_OUT_OF_RANGE",
                  "Instruction timestamp is outside the permitted window."));
      } catch (DateTimeException e) {
        issues.add(new Issue("date", "INVALID_DATE", "Use an ISO 8601 timestamp with an offset."));
      }
    String purpose = v.get("purpose") == null ? null : v.get("purpose").strip();
    String notes = v.get("notes") == null ? "" : v.get("notes").strip();
    if (purpose != null && purpose.length() > 255)
      issues.add(new Issue("purpose", "TOO_LONG", "Purpose exceeds 255 characters."));
    if (notes.length() > 1000)
      issues.add(new Issue("notes", "TOO_LONG", "Notes exceed 1000 characters."));
    if (!issues.isEmpty())
      throw new ApiException(
          400, "VALIDATION_FAILED", "Correct the reported fields.", id, issues, false);
    return new PaymentCommand(
        id, v.get("fromAccount"), v.get("toAccount"), amount, date, purpose, notes);
  }
}
