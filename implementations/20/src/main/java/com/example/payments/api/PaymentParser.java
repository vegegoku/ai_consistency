package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.StringReader;
import java.math.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
                      .maxNestingDepth(8)
                      .maxStringLength(65536)
                      .maxNumberLength(40)
                      .build())
              .build());

  @ConfigProperty(name = "payments.maximum", defaultValue = "10000.000")
  BigDecimal maximum = new BigDecimal("10000.000");

  @ConfigProperty(name = "payments.future-seconds", defaultValue = "300")
  long future = 300;

  @ConfigProperty(name = "payments.old-seconds", defaultValue = "2592000")
  long old = 2592000;

  public Map<String, String> parse(String body, boolean xml) {
    try {
      return xml ? xml(body) : json(body);
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ApiFailure(400, "MALFORMED_MESSAGE");
    }
  }

  private Map<String, String> json(String body) throws Exception {
    try (var p = mapper.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new ApiFailure(400, "MALFORMED_MESSAGE");
      Map<String, String> result = new HashMap<>();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME)
          throw new ApiFailure(400, "MALFORMED_MESSAGE");
        String field = p.currentName();
        if (!FIELDS.contains(field)) throw new ApiFailure(400, "MALFORMED_MESSAGE");
        JsonToken value = p.nextToken();
        if (value == JsonToken.VALUE_NULL) {
          result.put(field, null);
          continue;
        }
        if (value != JsonToken.VALUE_STRING
            && !(field.equals("amount") && value != null && value.isNumeric()))
          throw new ApiFailure(400, "MALFORMED_MESSAGE");
        result.put(field, p.getText());
      }
      if (p.nextToken() != null) throw new ApiFailure(400, "MALFORMED_MESSAGE");
      return result;
    }
  }

  private Map<String, String> xml(String body) throws Exception {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, b, c, d) -> {
          throw new XMLStreamException("External resources forbidden");
        });
    var r = f.createXMLStreamReader(new StringReader(body));
    Map<String, String> result = new HashMap<>();
    int depth = 0;
    String field = null;
    StringBuilder value = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException("Forbidden XML");
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException("Attributes/namespaces forbidden");
          String name = r.getLocalName();
          if (depth == 1) {
            if (root || !name.equals("payment")) throw new XMLStreamException("Root");
            root = true;
          } else if (depth == 2) {
            if (!FIELDS.contains(name) || result.containsKey(name))
              throw new XMLStreamException("Field");
            field = name;
            value.setLength(0);
          } else throw new XMLStreamException("Depth");
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) value.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException("Text");
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) result.put(field, value.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException("Root");
      return result;
    } finally {
      r.close();
    }
  }

  public PaymentCommand validate(Map<String, String> m) {
    List<ApiFailure.Issue> errors = new ArrayList<>();
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (m.get(f) == null || m.get(f).isBlank()) issue(errors, f, "Required value is missing.");
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    try {
      String raw = m.get("paymentId");
      id = UUID.fromString(raw);
      if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(errors, "paymentId", "A canonical UUID is required.");
    }
    try {
      String a = m.get("amount");
      if (a == null || !a.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
        throw new IllegalArgumentException();
      amount = new BigDecimal(a);
      if (amount.signum() <= 0 || amount.scale() > 3 || amount.compareTo(maximum) > 0)
        throw new IllegalArgumentException();
      amount = amount.setScale(3, RoundingMode.UNNECESSARY);
    } catch (Exception e) {
      issue(
          errors,
          "amount",
          "A positive amount with up to three decimals within the configured maximum is required.");
    }
    try {
      date = OffsetDateTime.parse(m.get("date")).toInstant();
      Instant now = Instant.now();
      if (date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(old)))
        throw new IllegalArgumentException();
    } catch (Exception e) {
      issue(errors, "date", "A recent offset-aware timestamp is required.");
    }
    for (String f : List.of("fromAccount", "toAccount"))
      if (m.get(f) != null && (m.get(f).length() > 100 || !m.get(f).matches("[A-Za-z0-9_-]+")))
        issue(errors, f, "Invalid account identifier.");
    if (m.get("fromAccount") != null && m.get("fromAccount").equals(m.get("toAccount")))
      issue(errors, "toAccount", "Accounts must differ.");
    String purpose = m.get("purpose") == null ? null : m.get("purpose").trim(),
        notes = m.get("notes") == null ? null : m.get("notes").trim();
    if (purpose != null && purpose.length() > 255)
      issue(errors, "purpose", "Maximum length is 255.");
    if (notes != null && notes.length() > 1000) issue(errors, "notes", "Maximum length is 1000.");
    if (!errors.isEmpty()) throw new ApiFailure(400, "VALIDATION_FAILED", errors);
    return new PaymentCommand(
        id, m.get("fromAccount"), m.get("toAccount"), amount, date, purpose, notes);
  }

  private void issue(List<ApiFailure.Issue> e, String f, String msg) {
    if (e.stream().noneMatch(i -> i.field().equals(f)))
      e.add(new ApiFailure.Issue(f, "VALIDATION_FAILED", msg));
  }
}
