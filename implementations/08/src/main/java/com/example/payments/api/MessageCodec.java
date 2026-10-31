package com.example.payments.api;

import com.example.payments.domain.PaymentCommand;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MessageCodec {
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private final ObjectMapper json =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(4)
                      .maxStringLength(65536)
                      .maxNumberLength(64)
                      .build())
              .build());

  @ConfigProperty(name = "payments.max-amount", defaultValue = "10000.000")
  String maxAmount = "10000.000";

  @ConfigProperty(name = "payments.max-age-seconds", defaultValue = "2592000")
  long maxAge = 2592000;

  @ConfigProperty(name = "payments.max-future-seconds", defaultValue = "300")
  long maxFuture = 300;

  public PaymentCommand parse(byte[] body, String type) {
    Map<String, String> values;
    if (body.length == 0) throw malformed();
    try {
      values = type.equals("application/xml") ? xml(body) : json(body);
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw malformed();
    }
    var issues = new ArrayList<ApiFailure.Issue>();
    String id = values.get("paymentId"),
        from = values.get("fromAccount"),
        to = values.get("toAccount"),
        purpose = values.get("purpose"),
        notes = values.get("notes");
    UUID uuid = null;
    BigDecimal amount = null;
    Instant date = null;
    for (String key : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (values.get(key) == null || values.get(key).isBlank())
        issue(issues, key, "REQUIRED", "A nonblank value is required.");
    if (id != null && !id.isBlank())
      try {
        if (!id.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new IllegalArgumentException();
        uuid = UUID.fromString(id);
      } catch (Exception e) {
        issue(issues, "paymentId", "INVALID_UUID", "A UUID is required.");
      }
    for (String key : List.of("fromAccount", "toAccount"))
      if (values.get(key) != null
          && (values.get(key).length() > 128 || !values.get(key).matches("[A-Za-z0-9_-]+")))
        issue(
            issues,
            key,
            "INVALID_ACCOUNT_ID",
            "Use 1 to 128 letters, digits, underscores or hyphens.");
    if (from != null && from.equals(to))
      issue(issues, "toAccount", "SAME_ACCOUNT", "Accounts must differ.");
    String raw = values.get("amount");
    if (raw != null && !raw.isBlank())
      try {
        if (!raw.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
        amount = new BigDecimal(raw);
        if (amount.scale() > 3)
          issue(
              issues,
              "amount",
              "EXCESS_PRECISION",
              "At most three fractional digits are permitted.");
        if (amount.signum() <= 0)
          issue(issues, "amount", "INVALID_AMOUNT", "Amount must be positive.");
        if (amount.compareTo(new BigDecimal(maxAmount)) > 0)
          issue(
              issues, "amount", "PAYMENT_LIMIT_EXCEEDED", "Amount exceeds the configured maximum.");
        if (amount.precision() > 19)
          issue(issues, "amount", "INVALID_AMOUNT", "Amount is too large.");
      } catch (Exception e) {
        issue(issues, "amount", "INVALID_AMOUNT", "A plain decimal amount is required.");
      }
    if (values.get("date") != null && !values.get("date").isBlank())
      try {
        OffsetDateTime parsedDate = OffsetDateTime.parse(values.get("date"));
        if (parsedDate.getYear() < 1 || parsedDate.getYear() > 9999)
          throw new IllegalArgumentException();
        date = parsedDate.toInstant();
      } catch (Exception e) {
        issue(issues, "date", "INVALID_DATE", "An ISO 8601 timestamp with offset is required.");
      }
    if (purpose != null) {
      purpose = purpose.trim();
      if (purpose.length() > 255) issue(issues, "purpose", "TOO_LONG", "Maximum 255 characters.");
    }
    if (notes != null) {
      notes = notes.trim();
      if (notes.length() > 1000) issue(issues, "notes", "TOO_LONG", "Maximum 1000 characters.");
    }
    // Preserve all safe field issues, while deferring clean historical retries to the stored
    // outcome.
    if (!issues.isEmpty() && date != null && outsideWindow(date))
      issue(
          issues, "date", "OUT_OF_WINDOW", "Instruction timestamp is outside the accepted window.");
    if (!issues.isEmpty())
      throw new ApiFailure(
          400,
          "VALIDATION_FAILED",
          "One or more fields are invalid.",
          uuid == null ? null : uuid.toString(),
          issues);
    return new PaymentCommand(
        uuid, from, to, amount.setScale(3), date, purpose, notes == null ? "" : notes);
  }

  public void validateFreshness(PaymentCommand p) {
    if (outsideWindow(p.date()))
      throw new ApiFailure(
          400,
          "VALIDATION_FAILED",
          "One or more fields are invalid.",
          p.paymentId().toString(),
          List.of(
              new ApiFailure.Issue(
                  "date",
                  "OUT_OF_WINDOW",
                  "Instruction timestamp is outside the accepted window.")));
  }

  private boolean outsideWindow(Instant date) {
    Instant now = Instant.now();
    return date.isAfter(now.plusSeconds(maxFuture)) || date.isBefore(now.minusSeconds(maxAge));
  }

  private Map<String, String> json(byte[] body) throws IOException {
    try (JsonParser parser = json.createParser(body)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) throw malformed();
      Map<String, String> values = new HashMap<>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) throw malformed();
        String key = parser.currentName();
        if (!FIELDS.contains(key)) throw malformed();
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
          values.put(key, null);
          continue;
        }
        if (token != JsonToken.VALUE_STRING
            && !(key.equals("amount")
                && (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)))
          throw malformed();
        String value = parser.getText();
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
          throw malformed();
        values.put(key, value);
      }
      if (parser.nextToken() != null) throw malformed();
      return values;
    }
  }

  private Map<String, String> xml(byte[] body) throws XMLStreamException {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver(
        (publicID, systemID, base, namespace) -> {
          throw new XMLStreamException("External resources prohibited");
        });
    XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(body));
    Map<String, String> values = new HashMap<>();
    int depth = 0;
    boolean root = false, ended = false;
    String field = null;
    StringBuilder content = new StringBuilder();
    try {
      while (r.hasNext()) {
        int event = r.next();
        if (event == XMLStreamConstants.DTD
            || event == XMLStreamConstants.ENTITY_REFERENCE
            || event == XMLStreamConstants.PROCESSING_INSTRUCTION) throw malformed();
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (ended
              || depth > 2
              || r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty())) throw malformed();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw malformed();
            root = true;
          } else {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || values.containsKey(field)) throw malformed();
            content.setLength(0);
          }
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (depth == 2) content.append(r.getText());
          else if (!r.isWhiteSpace()) throw malformed();
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) values.put(field, content.toString());
          if (depth == 1) ended = true;
          depth--;
        }
      }
    } finally {
      r.close();
    }
    if (!root || !ended || depth != 0) throw malformed();
    return values;
  }

  private static void issue(List<ApiFailure.Issue> out, String field, String code, String msg) {
    out.add(new ApiFailure.Issue(field, code, msg));
  }

  private static ApiFailure malformed() {
    return new ApiFailure(
        400, "MALFORMED_MESSAGE", "The request document is malformed or ambiguous.");
  }
}
