package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessageCodec {
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
  private final BigDecimal maximum;
  private final long age, future;
  private final int maxBody;
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");

  public MessageCodec(
      @Value("${payments.max-amount}") BigDecimal max,
      @Value("${payments.max-age-seconds}") long age,
      @Value("${payments.future-seconds}") long future,
      @Value("${payments.max-body}") int body) {
    json.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    json.setNodeFactory(
        com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
    this.maximum = max;
    this.age = age;
    this.future = future;
    maxBody = body;
  }

  public PaymentCommand read(InputStream input, String type) {
    try {
      byte[] bytes = input.readNBytes(maxBody + 1);
      if (bytes.length > maxBody) throw new ApiError(413, "VALIDATION_FAILED");
      if (bytes.length == 0) throw new ApiError(400, "MALFORMED_MESSAGE");
      Map<String, String> m;
      if (type != null && type.split(";")[0].trim().equalsIgnoreCase("application/json"))
        m = readJson(bytes);
      else if (type != null && type.split(";")[0].trim().equalsIgnoreCase("application/xml"))
        m = readXml(bytes);
      else throw new ApiError(415, "UNSUPPORTED_MEDIA_TYPE");
      return validate(m);
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(400, "MALFORMED_MESSAGE");
    }
  }

  private Map<String, String> readJson(byte[] b) throws Exception {
    JsonNode n = json.readTree(b);
    if (n == null || !n.isObject()) throw new ApiError(400, "MALFORMED_MESSAGE");
    try (JsonParser p = json.createParser(b)) {
      json.readTree(p);
      if (p.nextToken() != null) throw new ApiError(400, "MALFORMED_MESSAGE");
    }
    Map<String, String> m = new HashMap<>();
    n.fields()
        .forEachRemaining(
            e -> {
              if (!FIELDS.contains(e.getKey())) throw new ApiError(400, "MALFORMED_MESSAGE");
              JsonNode v = e.getValue();
              if (v.isNull()) {
                m.put(e.getKey(), null);
                return;
              }
              if (!v.isTextual() && !(e.getKey().equals("amount") && v.isNumber()))
                throw new ApiError(400, "MALFORMED_MESSAGE");
              m.put(e.getKey(), v.asText());
            });
    return m;
  }

  private Map<String, String> readXml(byte[] b) throws Exception {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, c, d, e) -> {
          throw new XMLStreamException("External resource denied");
        });
    XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(b));
    Map<String, String> m = new HashMap<>();
    int depth = 0;
    String field = null;
    StringBuilder value = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new ApiError(400, "MALFORMED_MESSAGE");
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new ApiError(400, "MALFORMED_MESSAGE");
          String name = r.getLocalName();
          if (depth == 1) {
            if (root || !name.equals("payment")) throw new ApiError(400, "MALFORMED_MESSAGE");
            root = true;
          } else if (depth == 2) {
            if (!FIELDS.contains(name) || m.containsKey(name))
              throw new ApiError(400, "MALFORMED_MESSAGE");
            field = name;
            value.setLength(0);
          } else throw new ApiError(400, "MALFORMED_MESSAGE");
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new ApiError(400, "MALFORMED_MESSAGE");
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) m.put(field, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new ApiError(400, "MALFORMED_MESSAGE");
      return m;
    } finally {
      r.close();
    }
  }

  private PaymentCommand validate(Map<String, String> m) {
    List<Map<String, String>> issues = new ArrayList<>();
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (m.get(f) == null || m.get(f).isBlank()) issue(issues, f, "REQUIRED");
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    try {
      id = UUID.fromString(m.get("paymentId"));
      if (!id.toString().equals(m.get("paymentId"))) throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(issues, "paymentId", "INVALID_UUID");
    }
    for (String f : List.of("fromAccount", "toAccount"))
      if (m.get(f) != null && (m.get(f).length() > 128 || !m.get(f).matches("[A-Za-z0-9_-]+")))
        issue(issues, f, "INVALID_ACCOUNT");
    try {
      String raw = m.get("amount");
      if (raw == null || raw.length() > 64 || !raw.matches("[0-9]+(?:\\.[0-9]+)?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(raw);
      if (amount.signum() <= 0
          || amount.scale() > 3
          || amount.compareTo(maximum) > 0
          || amount.precision() > 19) throw new IllegalArgumentException();
      amount = amount.setScale(3, RoundingMode.UNNECESSARY);
    } catch (Exception e) {
      issue(issues, "amount", "INVALID_AMOUNT");
    }
    try {
      date = OffsetDateTime.parse(m.get("date")).toInstant();
      Instant now = Instant.now();
      if (date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(age)))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(issues, "date", "INVALID_DATE");
    }
    if (Objects.equals(m.get("fromAccount"), m.get("toAccount")))
      issue(issues, "toAccount", "SAME_ACCOUNT");
    String purpose = m.get("purpose") == null ? null : m.get("purpose").trim();
    String notes = m.get("notes") == null ? null : m.get("notes").trim();
    if (purpose != null && purpose.length() > 255) issue(issues, "purpose", "TOO_LONG");
    if (notes != null && notes.length() > 1000) issue(issues, "notes", "TOO_LONG");
    if (!issues.isEmpty())
      throw new ApiError(400, "VALIDATION_FAILED", id == null ? null : id.toString(), issues);
    return new PaymentCommand(
        id, m.get("fromAccount"), m.get("toAccount"), amount, date, purpose, notes);
  }

  private static void issue(List<Map<String, String>> list, String f, String c) {
    list.add(Map.of("field", f, "code", c, "message", "Invalid field value."));
  }
}
