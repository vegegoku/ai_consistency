package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MessageParser {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final int maximumBytes;
  private final BigDecimal maximumAmount;
  private final Duration maxAge, maxFuture;

  @Inject
  public MessageParser(
      @ConfigProperty(name = "payment.max-body", defaultValue = "65536") int bytes,
      @ConfigProperty(name = "payment.max-amount", defaultValue = "10000.000") BigDecimal amount,
      @ConfigProperty(name = "payment.max-age", defaultValue = "P30D") Duration age,
      @ConfigProperty(name = "payment.max-future", defaultValue = "PT5M") Duration future) {
    maximumBytes = bytes;
    maximumAmount = amount;
    maxAge = age;
    maxFuture = future;
  }

  public PaymentCommand parse(byte[] body, String contentType) {
    if (body == null || body.length == 0) throw new PaymentException(400, "MALFORMED_MESSAGE");
    if (body.length > maximumBytes) throw new PaymentException(413, "PAYLOAD_TOO_LARGE");
    String media =
        contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    Map<String, String> values;
    try {
      values =
          switch (media) {
            case "application/json" -> json(body);
            case "application/xml" -> xml(body);
            default -> throw new PaymentException(415, "UNSUPPORTED_MEDIA_TYPE");
          };
    } catch (PaymentException e) {
      throw e;
    } catch (Exception e) {
      throw new PaymentException(400, "MALFORMED_MESSAGE");
    }
    return validate(values);
  }

  private Map<String, String> json(byte[] body) throws IOException {
    var factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxStringLength(maximumBytes)
                    .maxNumberLength(100)
                    .build())
            .build();
    var values = new HashMap<String, String>();
    try (var p = factory.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw malformed();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw malformed();
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw malformed();
        JsonToken token = p.nextToken();
        if (token == JsonToken.VALUE_NULL) {
          values.put(name, null);
          continue;
        }
        if (token != JsonToken.VALUE_STRING
            && !(name.equals("amount")
                && (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)))
          throw malformed();
        values.put(name, p.getText());
      }
      if (p.nextToken() != null) throw malformed();
    }
    return values;
  }

  private Map<String, String> xml(byte[] body) throws XMLStreamException {
    var f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External access forbidden");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(body));
    var values = new HashMap<String, String>();
    int depth = 0;
    String field = null;
    StringBuilder value = null;
    boolean root = false;
    try {
      while (r.hasNext()) {
        int t = r.next();
        if (t == XMLStreamConstants.DTD
            || t == XMLStreamConstants.ENTITY_REFERENCE
            || t == XMLStreamConstants.PROCESSING_INSTRUCTION) throw malformed();
        if (t == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty())) throw malformed();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw malformed();
            root = true;
          } else if (depth == 2) {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || values.containsKey(field)) throw malformed();
            value = new StringBuilder();
            values.put(field, "");
          } else throw malformed();
        } else if (t == XMLStreamConstants.CHARACTERS || t == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw malformed();
        } else if (t == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) values.put(field, value.toString());
          depth--;
        }
      }
    } finally {
      r.close();
    }
    if (!root || depth != 0) throw malformed();
    return values;
  }

  private PaymentCommand validate(Map<String, String> v) {
    var issues = new ArrayList<Issue>();
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (v.get(f) == null || v.get(f).isBlank())
        issues.add(new Issue(f, "REQUIRED", "A nonblank value is required."));
    String rawId = v.get("paymentId");
    if (rawId != null && !rawId.isBlank())
      try {
        if (!rawId.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new IllegalArgumentException();
        id = UUID.fromString(rawId);
      } catch (Exception e) {
        issues.add(new Issue("paymentId", "INVALID_UUID", "A UUID is required."));
      }
    for (String f : List.of("fromAccount", "toAccount"))
      if (v.get(f) != null && (v.get(f).length() > 128 || !v.get(f).equals(v.get(f).trim())))
        issues.add(
            new Issue(
                f,
                "INVALID_ACCOUNT_ID",
                "Account identifiers must be exact and at most 128 characters."));
    if (v.get("amount") != null && !v.get("amount").isBlank())
      try {
        if (!v.get("amount").matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
          throw new NumberFormatException();
        amount = new BigDecimal(v.get("amount"));
        if (amount.signum() <= 0 || amount.scale() > 3 || amount.compareTo(maximumAmount) > 0)
          issues.add(
              new Issue(
                  "amount",
                  "INVALID_AMOUNT",
                  "Amount must be positive, at most three decimal places, and within the payment"
                      + " maximum."));
        else amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issues.add(new Issue("amount", "INVALID_AMOUNT", "A decimal amount is required."));
      }
    if (v.get("date") != null && !v.get("date").isBlank())
      try {
        date = OffsetDateTime.parse(v.get("date")).toInstant();
        Instant now = Instant.now();
        if (date.isAfter(now.plus(maxFuture)) || date.isBefore(now.minus(maxAge)))
          issues.add(
              new Issue(
                  "date",
                  "OUTSIDE_WINDOW",
                  "Instruction timestamp is outside the allowed window."));
      } catch (Exception e) {
        issues.add(
            new Issue("date", "INVALID_DATE", "An ISO 8601 timestamp with offset is required."));
      }
    String purpose = v.get("purpose") == null ? null : v.get("purpose").trim();
    String notes = v.get("notes") == null ? null : v.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      issues.add(new Issue("purpose", "TOO_LONG", "Maximum length is 255."));
    if (notes != null && notes.length() > 1000)
      issues.add(new Issue("notes", "TOO_LONG", "Maximum length is 1000."));
    if (v.get("fromAccount") != null && v.get("fromAccount").equals(v.get("toAccount")))
      issues.add(new Issue("toAccount", "SAME_ACCOUNT", "Source and destination must differ."));
    if (!issues.isEmpty())
      throw new PaymentException(
          400, "VALIDATION_FAILED", issues, id == null ? null : id.toString());
    return new PaymentCommand(
        id, v.get("fromAccount"), v.get("toAccount"), amount, date, purpose, notes);
  }

  private PaymentException malformed() {
    return new PaymentException(400, "MALFORMED_MESSAGE");
  }
}
