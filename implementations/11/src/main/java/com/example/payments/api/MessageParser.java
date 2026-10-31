package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessageParser {
  private final int maxBytes;
  private final BigDecimal maxAmount;
  private final long future, age;
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
              .build());
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");

  public MessageParser(
      @Value("${payment.max-body-bytes:65536}") int bytes,
      @Value("${payment.max-amount:10000.000}") String amount,
      @Value("${payment.max-future-seconds:300}") long future,
      @Value("${payment.max-age-seconds:2592000}") long age) {
    json.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    json.setNodeFactory(
        com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    maxBytes = bytes;
    maxAmount = new BigDecimal(amount);
    this.future = future;
    this.age = age;
  }

  public PaymentCommand parse(byte[] bytes, String type) {
    return parse(bytes, type, true);
  }

  public PaymentCommand parse(byte[] bytes, String type, boolean dateWindow) {
    if (bytes.length > maxBytes) throw new ApiFailure(413, "PAYLOAD_TOO_LARGE");
    if (bytes.length == 0) throw new ApiFailure(400, "MALFORMED_MESSAGE");
    Map<String, String> fields;
    try {
      if (type.equals("application/json")) fields = readJson(bytes);
      else if (type.equals("application/xml")) fields = readXml(bytes);
      else throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    }
    return validate(fields, dateWindow);
  }

  private Map<String, String> readJson(byte[] bytes) throws Exception {
    try (var p = json.createParser(bytes)) {
      JsonNode root = json.readTree(p);
      if (root == null || !root.isObject() || p.nextToken() != null)
        throw new ApiFailure(400, "MALFORMED_MESSAGE");
      Map<String, String> fields = new HashMap<>();
      var names = root.fieldNames();
      while (names.hasNext()) {
        String key = names.next();
        JsonNode v = root.get(key);
        if (!FIELDS.contains(key)
            || (!v.isNull() && !v.isTextual() && !(key.equals("amount") && v.isNumber())))
          throw new ApiFailure(400, "MALFORMED_MESSAGE");
        fields.put(key, v.isNull() ? null : v.asText());
      }
      return fields;
    }
  }

  private Map<String, String> readXml(byte[] bytes) throws Exception {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    var r = f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    Map<String, String> fields = new HashMap<>();
    int depth = 0;
    String key = null;
    StringBuilder value = null;
    boolean root = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException("Unsafe XML");
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (depth > 2
              || r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || !Objects.toString(r.getNamespaceURI(), "").isEmpty())
            throw new XMLStreamException("Invalid XML structure");
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else {
            key = r.getLocalName();
            if (!FIELDS.contains(key) || fields.containsKey(key)) throw new XMLStreamException();
            value = new StringBuilder();
          }
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) fields.put(key, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return fields;
    } finally {
      r.close();
    }
  }

  private PaymentCommand validate(Map<String, String> f, boolean dateWindow) {
    List<ApiFailure.Issue> issues = new ArrayList<>();
    for (String key : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (f.get(key) == null || f.get(key).isBlank()) issue(issues, key, "REQUIRED");
    String id = f.get("paymentId");
    boolean validId =
        id != null
            && id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    if (id != null && !validId) issue(issues, "paymentId", "INVALID_UUID");
    for (String key : List.of("fromAccount", "toAccount"))
      if (f.get(key) != null && f.get(key).length() > 128) issue(issues, key, "TOO_LONG");
    if (f.get("fromAccount") != null && f.get("fromAccount").equals(f.get("toAccount")))
      issue(issues, "toAccount", "SAME_ACCOUNT");
    String purpose = f.getOrDefault("purpose", "");
    purpose = purpose == null ? "" : purpose.trim();
    String notes = Objects.toString(f.get("notes"), "").trim();
    if (purpose.length() > 255) issue(issues, "purpose", "TOO_LONG");
    if (notes.length() > 1000) issue(issues, "notes", "TOO_LONG");
    BigDecimal amount = null;
    try {
      amount = new BigDecimal(f.get("amount"));
      if (amount.signum() <= 0) issue(issues, "amount", "NOT_POSITIVE");
      if (amount.scale() > 3 || amount.precision() > 19 || amount.compareTo(maxAmount) > 0)
        issue(issues, "amount", "INVALID_AMOUNT");
      if (amount.scale() <= 3 && amount.precision() <= 19 && amount.compareTo(maxAmount) <= 0)
        amount = amount.setScale(3);
    } catch (Exception e) {
      issue(issues, "amount", "INVALID_AMOUNT");
    }
    Instant date = null;
    try {
      date = OffsetDateTime.parse(f.get("date")).toInstant();
      Instant now = Instant.now();
      if (dateWindow
          && (date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(age))))
        issue(issues, "date", "OUTSIDE_WINDOW");
    } catch (Exception e) {
      issue(issues, "date", "INVALID_TIMESTAMP");
    }
    if (!issues.isEmpty())
      throw new ApiFailure(400, "VALIDATION_FAILED", issues, validId ? id : null);
    return new PaymentCommand(
        id, f.get("fromAccount"), f.get("toAccount"), amount, date, purpose, notes);
  }

  private void issue(List<ApiFailure.Issue> list, String field, String code) {
    list.add(
        new ApiFailure.Issue(field, code, "The field does not meet the required constraints."));
  }
}
