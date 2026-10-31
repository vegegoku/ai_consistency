package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

@Component
@RestControllerAdvice
public class ApiErrors {
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public ApiErrors(io.micrometer.core.instrument.MeterRegistry metrics) {
    this.metrics = metrics;
  }

  public static final ObjectMapper JSON = new ObjectMapper();
  public static final XmlMapper XML = new XmlMapper();

  public Map<String, Object> body(PaymentException e, String correlation) {
    var b = new LinkedHashMap<String, Object>();
    if (e.paymentId != null) b.put("paymentId", e.paymentId);
    b.put("status", "ERROR");
    b.put("code", e.code);
    b.put(
        "message",
        e.http == 503
            ? "Processing outcome may be unknown; query or retry the same payment ID."
            : "The request could not be completed.");
    b.put("issues", e.issues);
    b.put("correlationId", correlation);
    b.put("timestamp", Instant.now().toString());
    return b;
  }

  @ExceptionHandler(PaymentException.class)
  public ResponseEntity<byte[]> payment(PaymentException e, HttpServletRequest r) {
    metrics.counter("payments.errors", "code", e.code).increment();
    return response(r, e.http, body(e, cid(r)));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> unexpected(Exception e, HttpServletRequest r) {
    boolean infrastructure =
        e instanceof org.springframework.dao.DataAccessException
            || e instanceof org.springframework.transaction.TransactionException;
    metrics.counter("payments.unexpected.failures").increment();
    String code = infrastructure ? "TEMPORARILY_UNAVAILABLE" : "INTERNAL_ERROR";
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .atError()
        .addKeyValue("correlationId", cid(r))
        .addKeyValue("code", code)
        .log("Request failed");
    return response(
        r,
        infrastructure ? 503 : 500,
        body(new PaymentException(infrastructure ? 503 : 500, code), cid(r)));
  }

  public String cid(HttpServletRequest r) {
    return Objects.toString(r.getAttribute("correlationId"), UUID.randomUUID().toString());
  }

  public String media(HttpServletRequest r) {
    String accept = r.getHeader("Accept");
    if (accept == null || accept.isBlank()) return "application/json";
    try {
      var types = MediaType.parseMediaTypes(accept);
      types.sort(MediaType.QUALITY_VALUE_COMPARATOR);
      for (var t : types) {
        if (t.getQualityValue() == 0) continue;
        if (t.isCompatibleWith(MediaType.APPLICATION_JSON)) return "application/json";
        if (t.isCompatibleWith(MediaType.APPLICATION_XML)) return "application/xml";
      }
    } catch (Exception ignored) {
    }
    throw new PaymentException(406, "NOT_ACCEPTABLE");
  }

  public ResponseEntity<byte[]> response(
      HttpServletRequest r, int status, Map<String, Object> body) {
    String media;
    try {
      media = media(r);
    } catch (PaymentException e) {
      media = "application/json";
      status = 406;
      body = body(e, cid(r));
    }
    try {
      var builder =
          ResponseEntity.status(status)
              .contentType(MediaType.parseMediaType(media))
              .header("X-Correlation-ID", cid(r));
      if (status == 429 || status == 503) builder.header("Retry-After", "2");
      if (status == 401) builder.header("WWW-Authenticate", "Bearer");
      return builder.body(
          media.equals("application/xml")
              ? XML.writer().withRootName("response").writeValueAsBytes(body)
              : JSON.writeValueAsBytes(body));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public void write(
      HttpServletRequest request, HttpServletResponse response, int status, String code)
      throws IOException {
    metrics.counter("payments.errors", "code", code).increment();
    var result = response(request, status, body(new PaymentException(status, code), cid(request)));
    response.setStatus(result.getStatusCode().value());
    result.getHeaders().forEach((k, v) -> response.setHeader(k, String.join(",", v)));
    response.getOutputStream().write(Objects.requireNonNull(result.getBody()));
  }
}
