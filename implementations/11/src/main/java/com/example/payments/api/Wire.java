package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;

public final class Wire {
  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private static final XmlMapper XML = new XmlMapper();

  private Wire() {}

  public static MediaType negotiate(String accept) {
    if (accept == null || accept.isBlank()) return MediaType.APPLICATION_JSON;
    try {
      var ranges = MediaType.parseMediaTypes(accept);
      MediaType best = null;
      double bestQuality = 0;
      for (var candidate : List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML)) {
        int specificity = -1;
        double quality = 0;
        for (var range : ranges)
          if (range.isCompatibleWith(candidate)) {
            int current = range.isWildcardType() ? 0 : range.isWildcardSubtype() ? 1 : 2;
            if (current > specificity) {
              specificity = current;
              quality = range.getQualityValue();
            } else if (current == specificity) quality = Math.max(quality, range.getQualityValue());
          }
        if (quality > bestQuality) {
          best = candidate;
          bestQuality = quality;
        }
      }
      if (best != null) return best;
    } catch (Exception ignored) {
    }
    throw new ApiFailure(406, "NOT_ACCEPTABLE");
  }

  public static String encode(Object body, MediaType type) {
    try {
      return type.equals(MediaType.APPLICATION_XML)
          ? XML.writer().withRootName("response").writeValueAsString(body)
          : JSON.writeValueAsString(body);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static ResponseEntity<String> response(
      int status, Object body, HttpServletRequest request) {
    MediaType type = negotiate(request.getHeader("Accept"));
    return ResponseEntity.status(status)
        .contentType(new MediaType(type, java.nio.charset.StandardCharsets.UTF_8))
        .body(encode(body, type));
  }

  public static String correlation(HttpServletRequest request) {
    return Objects.toString(request.getAttribute("correlationId"), UUID.randomUUID().toString());
  }

  public static void error(HttpServletRequest req, HttpServletResponse res, int status, String code)
      throws java.io.IOException {
    MediaType type;
    try {
      type = negotiate(req.getHeader("Accept"));
    } catch (ApiFailure e) {
      type = MediaType.APPLICATION_JSON;
      status = 406;
      code = "NOT_ACCEPTABLE";
    }
    req.setAttribute("errorCode", code);
    res.setStatus(status);
    res.setContentType(type.toString());
    res.setCharacterEncoding("UTF-8");
    if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
    if (status == 429 || status == 503) res.setHeader("Retry-After", "1");
    res.getWriter()
        .write(encode(PaymentService.error(status, code, null, correlation(req)).body(), type));
  }
}
