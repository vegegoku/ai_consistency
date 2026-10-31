package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class PaymentParser {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final ObjectMapper mapper =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(16)
                      .maxStringLength(65536)
                      .maxNumberLength(100)
                      .build())
              .build());

  private long config(String key, long fallback) {
    return ConfigProvider.getConfig().getOptionalValue(key, Long.class).orElse(fallback);
  }

  public PaymentCommand parse(String body, String type) {
    if (body == null || body.isBlank()) throw new ApiFailure(400, "MALFORMED_MESSAGE");
    if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > config("payments.max-body-bytes", 65536)) throw new ApiFailure(413, "MALFORMED_MESSAGE");
    Map<String, String> values;
    try {
      values =
          type.startsWith("application/json")
              ? json(body)
              : type.startsWith("application/xml") ? xml(body) : null;
      if (values == null) throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    }
    var issues = new ArrayList<ApiFailure.Issue>();
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (values.get(f) == null || values.get(f).isBlank())
        issue(issues, f, "Required nonblank value.");
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    try {
      String s = values.get("paymentId");
      id = UUID.fromString(s);
      if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(issues, "paymentId", "A canonical UUID is required.");
    }
    try {
      String s = values.get("amount");
      if (s == null || !s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(s);
      if (amount.signum() <= 0
          || amount.scale() > 3
          || amount.compareTo(
                  new BigDecimal(
                      ConfigProvider.getConfig()
                          .getOptionalValue("payments.maximum", String.class)
                          .orElse("10000.000")))
              > 0) throw new IllegalArgumentException();
      amount = amount.setScale(3);
    } catch (Exception e) {
      issue(
          issues,
          "amount",
          "A positive decimal with at most three fractional digits within the payment maximum is"
              + " required.");
    }
    try {
      date = OffsetDateTime.parse(values.get("date")).toInstant();
      Instant now = Instant.now();
      if (date.isAfter(now.plusSeconds(config("payments.future-seconds", 300)))
          || date.isBefore(now.minusSeconds(config("payments.max-age-seconds", 2592000))))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(
          issues, "date", "An offset timestamp within the allowed instruction window is required.");
    }
    if (Objects.equals(values.get("fromAccount"), values.get("toAccount"))
        && values.get("fromAccount") != null)
      issue(issues, "toAccount", "Source and destination must differ.");
    for (String f : List.of("fromAccount", "toAccount"))
      if (values.get(f) != null && values.get(f).length() > 128)
        issue(issues, f, "Maximum 128 characters.");
    String purpose = values.get("purpose"), notes = values.get("notes");
    if (purpose != null) {
      purpose = purpose.strip();
      if (purpose.length() > 255) issue(issues, "purpose", "Maximum 255 characters.");
    }
    if (notes != null) {
      notes = notes.strip();
      if (notes.length() > 1000) issue(issues, "notes", "Maximum 1000 characters.");
    }
    if (!issues.isEmpty())
      throw new ApiFailure(400, "VALIDATION_FAILED", issues)
          .payment(id == null ? null : id.toString());
    return new PaymentCommand(
        id, values.get("fromAccount"), values.get("toAccount"), amount, date, purpose, notes);
  }

  private void issue(List<ApiFailure.Issue> issues, String field, String message) {
    issues.add(new ApiFailure.Issue(field, "VALIDATION_FAILED", message));
  }

  private Map<String, String> json(String body) throws IOException {
    var map = new HashMap<String, String>();
    try (var p = mapper.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String name = p.currentName();
        if (!FIELDS.contains(name)) throw new IOException();
        JsonToken token = p.nextToken();
        if (token == JsonToken.VALUE_NULL) map.put(name, null);
        else if (token == JsonToken.VALUE_STRING || (name.equals("amount") && token.isNumeric()))
          map.put(name, p.getText());
        else throw new IOException();
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return map;
  }

  private Map<String, String> xml(String body) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    factory.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources forbidden");
        });
    var r = factory.createXMLStreamReader(new StringReader(body));
    var map = new HashMap<String, String>();
    boolean root = false, closed = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException();
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (r.getAttributeCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          String name = r.getLocalName();
          if (!root) {
            if (!name.equals("payment")) throw new XMLStreamException();
            root = true;
          } else {
            if (closed || !FIELDS.contains(name) || map.containsKey(name))
              throw new XMLStreamException();
            map.put(name, r.getElementText());
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (!r.getLocalName().equals("payment")) throw new XMLStreamException();
          closed = true;
        } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
            && !r.isWhiteSpace()) throw new XMLStreamException();
      }
      if (!root || !closed) throw new XMLStreamException();
      return map;
    } finally {
      r.close();
    }
  }
}
