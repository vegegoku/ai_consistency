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
  private final BigDecimal maxAmount;
  private final long futureSeconds, pastSeconds;
  private final Clock clock;
  private final JsonFactory json =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(4)
                  .maxNumberLength(40)
                  .maxStringLength(65536)
                  .build())
          .build();

  public MessageParser(
      @Value("${payments.max-body-bytes:65536}") int maxBytes,
      @Value("${payments.max-amount:10000.000}") String maxAmount,
      @Value("${payments.future-seconds:300}") long futureSeconds,
      @Value("${payments.past-seconds:2592000}") long pastSeconds,
      Clock clock) {
    this.maxBytes = maxBytes;
    this.maxAmount = new BigDecimal(maxAmount);
    this.futureSeconds = futureSeconds;
    this.pastSeconds = pastSeconds;
    this.clock = clock;
  }

  public PaymentCommand parse(byte[] body, String contentType) {
    return parse(body, contentType, true);
  }

  /** Only used to compare an already stored instruction; never to execute a new transfer. */
  public PaymentCommand parseReplayCandidate(byte[] body, String contentType) {
    return parse(body, contentType, false);
  }

  private PaymentCommand parse(byte[] body, String contentType, boolean currentPolicy) {
    if (body == null || body.length == 0)
      throw new PaymentException(400, "MALFORMED_MESSAGE", "A payment document is required.");
    if (body.length > maxBytes)
      throw new PaymentException(413, "PAYLOAD_TOO_LARGE", "Request exceeds the configured limit.");
    String media =
        contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    try {
      Map<String, String> fields =
          switch (media) {
            case "application/json" -> parseJson(body);
            case "application/xml" -> parseXml(body);
            default ->
                throw new PaymentException(
                    415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json or application/xml.");
          };
      return validate(fields, currentPolicy);
    } catch (PaymentException e) {
      throw e;
    } catch (Exception e) {
      throw new PaymentException(
          400, "MALFORMED_MESSAGE", "The document is malformed or contains invalid typed values.");
    }
  }

  private Map<String, String> parseJson(byte[] body) throws IOException {
    Map<String, String> result = new HashMap<>();
    try (JsonParser p = json.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          result.put(name, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(name.equals("amount") && t.isNumeric()))
          throw new IOException();
        result.put(name, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return result;
  }

  private Map<String, String> parseXml(byte[] body) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(body));
    Map<String, String> result = new HashMap<>();
    int depth = 0;
    boolean rootSeen = false, rootClosed = false;
    String field = null;
    StringBuilder value = new StringBuilder();
    try {
      while (r.hasNext()) {
        int t = r.next();
        if (t == XMLStreamConstants.DTD
            || t == XMLStreamConstants.ENTITY_REFERENCE
            || t == XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
        if (t == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || !Objects.toString(r.getNamespaceURI(), "").isEmpty())
            throw new XMLStreamException();
          if (depth == 1) {
            if (rootSeen || rootClosed || !r.getLocalName().equals("payment"))
              throw new XMLStreamException();
            rootSeen = true;
          } else if (depth == 2) {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || result.containsKey(field))
              throw new XMLStreamException();
            value.setLength(0);
          } else throw new XMLStreamException();
        } else if (t == XMLStreamConstants.CHARACTERS || t == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (t == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) result.put(field, value.toString());
          if (depth == 1) rootClosed = true;
          depth--;
        }
      }
    } finally {
      r.close();
    }
    if (!rootSeen || !rootClosed || depth != 0) throw new XMLStreamException();
    return result;
  }

  private PaymentCommand validate(Map<String, String> f, boolean currentPolicy) {
    List<Issue> issues = new ArrayList<>();
    for (var entry : f.entrySet())
      if (entry.getValue() != null && !validUnicodeText(entry.getValue()))
        issue(
            issues,
            entry.getKey(),
            "INVALID_TEXT",
            "Text must contain valid Unicode without null characters.");
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String name :
        List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (f.get(name) == null || f.get(name).isBlank())
        issue(issues, name, "REQUIRED", "A nonblank value is required.");
    String rawId = f.get("paymentId");
    if (rawId != null && !rawId.isBlank())
      try {
        id = UUID.fromString(rawId);
        if (!id.toString().equals(rawId)) throw new IllegalArgumentException();
      } catch (Exception e) {
        id = null;
        issue(issues, "paymentId", "INVALID_UUID", "A canonical lowercase UUID is required.");
      }
    for (String name : List.of("fromAccount", "toAccount")) {
      String s = f.get(name);
      if (s != null && (s.length() > 128 || s.chars().anyMatch(Character::isISOControl)))
        issue(
            issues,
            name,
            "INVALID_ACCOUNT_ID",
            "Account identifiers must contain 1 to 128 non-control characters.");
    }
    if (f.get("fromAccount") != null && f.get("fromAccount").equals(f.get("toAccount")))
      issue(issues, "toAccount", "SAME_ACCOUNT", "Source and destination must differ.");
    String rawAmount = f.get("amount");
    if (rawAmount != null && !rawAmount.isBlank()) {
      if (!rawAmount.matches("-?[0-9]+(?:\\.[0-9]+)?") || rawAmount.length() > 40)
        throw new PaymentException(
            400,
            "MALFORMED_MESSAGE",
            "Amount must be an ordinary decimal number or decimal string.");
      amount = new BigDecimal(rawAmount);
      if (amount.signum() <= 0)
        issue(issues, "amount", "INVALID_AMOUNT", "Amount must be greater than zero.");
      if (amount.scale() > 3)
        issue(issues, "amount", "EXCESS_PRECISION", "At most three fractional digits are allowed.");
      if (currentPolicy && amount.compareTo(maxAmount) > 0)
        issue(issues, "amount", "PAYMENT_LIMIT_EXCEEDED", "Amount exceeds the configured maximum.");
    }
    String rawDate = f.get("date");
    if (rawDate != null && !rawDate.isBlank())
      try {
        date = OffsetDateTime.parse(rawDate).toInstant();
        Instant now = clock.instant();
        if (currentPolicy
            && (date.isAfter(now.plusSeconds(futureSeconds))
                || date.isBefore(now.minusSeconds(pastSeconds))))
          issue(
              issues,
              "date",
              "DATE_OUT_OF_RANGE",
              "Instruction timestamp is outside the permitted window.");
      } catch (DateTimeException e) {
        issue(issues, "date", "INVALID_DATE", "An ISO 8601 timestamp with an offset is required.");
      }
    String purpose = f.get("purpose");
    String notes = f.get("notes");
    if (purpose != null && purpose.length() > 255)
      issue(issues, "purpose", "TOO_LONG", "Purpose must not exceed 255 characters.");
    if (notes != null && notes.length() > 1000)
      issue(issues, "notes", "TOO_LONG", "Notes must not exceed 1000 characters.");
    if (!issues.isEmpty())
      throw new PaymentException(
          400, "VALIDATION_FAILED", "The payment fields are invalid.", issues, id);
    return new PaymentCommand(
        id,
        f.get("fromAccount"),
        f.get("toAccount"),
        amount.setScale(3, RoundingMode.UNNECESSARY),
        date,
        purpose.trim(),
        notes == null ? "" : notes.trim());
  }

  private boolean validUnicodeText(String value) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == 0) return false;
      if (Character.isHighSurrogate(current)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
      } else if (Character.isLowSurrogate(current)) return false;
    }
    return true;
  }

  private void issue(List<Issue> issues, String field, String code, String message) {
    issues.add(new Issue(field, code, message));
  }
}
