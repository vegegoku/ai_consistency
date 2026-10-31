package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class ResponseWriter {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ResponseWriter.class);
  private final ObjectMapper json;
  private final XmlMapper xml;

  public ResponseWriter(ObjectMapper json) {
    this.json = json;
    this.xml = XmlMapper.builder().findAndAddModules().build();
  }

  public MediaType negotiate(String accept) {
    if (accept == null || accept.isBlank()) return MediaType.APPLICATION_JSON;
    try {
      var ranges = MediaType.parseMediaTypes(accept);
      MediaType best = null;
      double quality = -1;
      for (var candidate : List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML)) {
        MediaType selected = null;
        int specificity = -1;
        for (var range : ranges)
          if (range.isCompatibleWith(candidate)) {
            int score = range.isWildcardType() ? 0 : range.isWildcardSubtype() ? 1 : 2;
            if (score > specificity
                || (score == specificity
                    && selected != null
                    && range.getQualityValue() > selected.getQualityValue())) {
              specificity = score;
              selected = range;
            }
          }
        if (selected != null
            && selected.getQualityValue() > 0
            && selected.getQualityValue() > quality) {
          best = candidate;
          quality = selected.getQualityValue();
        }
      }
      if (best != null) return best;
    } catch (IllegalArgumentException ignored) {
    }
    throw new PaymentException(
        406, "NOT_ACCEPTABLE", "Use application/json or application/xml responses.");
  }

  public String serialize(Object body, MediaType type) {
    try {
      return type.equals(MediaType.APPLICATION_XML)
          ? xml.writer().withRootName("response").writeValueAsString(body)
          : json.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("Response serialization failed", e);
    }
  }

  public ResponseEntity<String> response(int status, Object body, HttpServletRequest request) {
    MediaType type = negotiate(request.getHeader("Accept"));
    return ResponseEntity.status(status).contentType(type).body(serialize(body, type));
  }

  public void error(
      HttpServletRequest request, HttpServletResponse response, PaymentException error)
      throws IOException {
    MediaType type;
    try {
      type = negotiate(request.getHeader("Accept"));
    } catch (PaymentException ignored) {
      type = MediaType.APPLICATION_JSON;
    }
    try {
      Object started = request.getAttribute("requestStartedNanos");
      log.atWarn()
          .addKeyValue("correlationId", request.getAttribute("correlationId"))
          .addKeyValue("paymentId", error.paymentId())
          .addKeyValue("status", "ERROR")
          .addKeyValue("code", error.code())
          .addKeyValue(
              "latencyMs",
              started instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : 0)
          .log("API request refused");
    } catch (RuntimeException ignored) {
    }
    response.setStatus(error.httpStatus());
    response.setContentType(type.toString());
    if (error.httpStatus() == 503 || error.httpStatus() == 429)
      response.setHeader("Retry-After", "2");
    response
        .getWriter()
        .write(
            serialize(
                ErrorBodies.create(
                    error,
                    Objects.toString(
                        request.getAttribute("correlationId"), UUID.randomUUID().toString()),
                    Instant.now()),
                type));
  }
}
