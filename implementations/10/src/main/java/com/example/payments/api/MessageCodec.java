package com.example.payments.api;

import com.example.payments.application.Settings;
import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;

@ApplicationScoped
public class MessageCodec {
  @Inject Settings config;
  private static final Set<String> FIELDS =
      Set.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final XmlMapper XML = new XmlMapper();

  public String encode(Object value, boolean xml) {
    try {
      return xml
          ? XML.writer().withRootName("response").writeValueAsString(value)
          : JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Command decode(byte[] body, String media) {
    return decode(body, media, id -> false);
  }

  public Command decode(byte[] body, String media, java.util.function.Predicate<UUID> recorded) {
    if (body == null || body.length == 0) throw new Problem(400, "MALFORMED_MESSAGE");
    if (body.length > config.maxBody) throw new Problem(413, "PAYLOAD_TOO_LARGE");
    Map<String, String> values;
    try {
      values = media.equals("application/json") ? json(body) : xml(body);
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(400, "MALFORMED_MESSAGE");
    }
    List<Map<String, String>> issues = new ArrayList<>();
    for (String f : List.of("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"))
      if (values.get(f) == null || values.get(f).isBlank())
        issues.add(Problem.issue(f, "REQUIRED", "A value is required."));
    UUID id = null;
    BigDecimal amount = null;
    Instant date = null;
    if (values.get("paymentId") != null)
      try {
        String s = values.get("paymentId");
        if (!s.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
          throw new Exception();
        id = UUID.fromString(s);
        if (!s.equals(id.toString())) throw new Exception();
      } catch (Exception e) {
        id = null;
        issues.add(Problem.issue("paymentId", "INVALID_UUID", "Use a canonical lower-case UUID."));
      }
    if (values.get("amount") != null)
      try {
        String s = values.get("amount");
        if (!s.matches("-?\\d+(\\.\\d+)?")) throw new Exception();
        amount = new BigDecimal(s);
        if (amount.signum() <= 0 || amount.scale() > 3) throw new Exception();
        if (amount.compareTo(config.maxAmount) > 0 && !(id != null && recorded.test(id)))
          issues.add(
              Problem.issue(
                  "amount", "PAYMENT_LIMIT_EXCEEDED", "Amount exceeds the configured maximum."));
        amount = amount.setScale(3, RoundingMode.UNNECESSARY);
      } catch (Exception e) {
        issues.add(
            Problem.issue(
                "amount",
                "INVALID_AMOUNT",
                "Use a positive decimal with at most three fractional digits."));
      }
    if (values.get("date") != null) {
      try {
        date = OffsetDateTime.parse(values.get("date")).toInstant();
      } catch (java.time.format.DateTimeParseException e) {
        issues.add(Problem.issue("date", "INVALID_DATE", "Use an offset-aware ISO timestamp."));
      }
      if (date != null) {
        Instant now = Instant.now();
        if ((date.isAfter(now.plusSeconds(config.future))
                || date.isBefore(now.minusSeconds(config.maxAge)))
            && !(id != null && recorded.test(id)))
          issues.add(
              Problem.issue(
                  "date",
                  "INVALID_DATE",
                  "Use an offset timestamp within the permitted time window."));
      }
    }
    String from = values.get("fromAccount"), to = values.get("toAccount");
    for (String f : List.of("fromAccount", "toAccount"))
      if (values.get(f) != null
          && (values.get(f).length() > 128 || !values.get(f).equals(values.get(f).strip())))
        issues.add(
            Problem.issue(f, "INVALID_ACCOUNT", "Account identifier has invalid formatting."));
    if (from != null && from.equals(to))
      issues.add(Problem.issue("toAccount", "SAME_ACCOUNT", "Accounts must differ."));
    String purpose = values.get("purpose"), notes = values.get("notes");
    if (purpose != null) purpose = purpose.strip();
    if (notes != null) notes = notes.strip();
    if (purpose != null && purpose.length() > 255)
      issues.add(Problem.issue("purpose", "TOO_LONG", "Maximum length is 255."));
    if (notes != null && notes.length() > 1000)
      issues.add(Problem.issue("notes", "TOO_LONG", "Maximum length is 1000."));
    if (!issues.isEmpty())
      throw new Problem(400, "VALIDATION_FAILED", id == null ? null : id.toString(), issues);
    try {
      String canonical =
          JSON.writeValueAsString(
              Arrays.asList(from, to, amount.toPlainString(), date.toString(), purpose, notes));
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8)));
      return new Command(id, from, to, amount, date, purpose, notes, hash);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, String> json(byte[] b) throws IOException {
    Map<String, String> m = new HashMap<>();
    try (JsonParser p = JSON.getFactory().createParser(b)) {
      if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME) throw new IOException();
        String f = p.currentName();
        if (!FIELDS.contains(f) || m.containsKey(f)) throw new IOException();
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NULL) {
          m.put(f, null);
          continue;
        }
        if (t != JsonToken.VALUE_STRING && !(f.equals("amount") && t.isNumeric()))
          throw new IOException();
        m.put(f, p.getText());
      }
      if (p.nextToken() != null) throw new IOException();
    }
    return m;
  }

  private Map<String, String> xml(byte[] b) throws XMLStreamException {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    f.setXMLResolver(
        (a, c, d, e) -> {
          throw new XMLStreamException("External resources forbidden");
        });
    XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(b));
    Map<String, String> m = new HashMap<>();
    int depth = 0;
    String field = null;
    StringBuilder text = new StringBuilder();
    boolean root = false;
    try {
      while (r.hasNext()) {
        int e = r.next();
        if (e == XMLStreamConstants.DTD || e == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException();
        if (e == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (r.getAttributeCount() != 0
              || r.getNamespaceCount() != 0
              || (r.getNamespaceURI() != null && !r.getNamespaceURI().isEmpty()))
            throw new XMLStreamException();
          if (depth == 1) {
            if (root || !r.getLocalName().equals("payment")) throw new XMLStreamException();
            root = true;
          } else if (depth == 2) {
            field = r.getLocalName();
            if (!FIELDS.contains(field) || m.containsKey(field)) throw new XMLStreamException();
            text.setLength(0);
          } else throw new XMLStreamException();
        } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
          if (depth == 2) text.append(r.getText());
          else if (!r.isWhiteSpace()) throw new XMLStreamException();
        } else if (e == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2) m.put(field, text.toString());
          depth--;
        }
      }
      if (!root || depth != 0) throw new XMLStreamException();
      return m;
    } finally {
      r.close();
    }
  }
}
