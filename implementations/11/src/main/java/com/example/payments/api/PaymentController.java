package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final MessageParser parser;
  private final PaymentService service;
  private final int limit;

  public PaymentController(
      MessageParser parser,
      PaymentService service,
      @Value("${payment.max-body-bytes:65536}") int limit) {
    this.parser = parser;
    this.service = service;
    this.limit = limit;
  }

  @PostMapping("/payments")
  public ResponseEntity<String> transfer(HttpServletRequest req, Authentication auth)
      throws java.io.IOException {
    Wire.negotiate(req.getHeader("Accept"));
    String type;
    try {
      MediaType media = MediaType.parseMediaType(req.getContentType());
      if (media.getCharset() != null
          && !media.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8))
        throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
      type = media.getType() + "/" + media.getSubtype();
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    }
    if (!type.equals("application/json") && !type.equals("application/xml"))
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] body = req.getInputStream().readNBytes(limit + 1);
    com.example.payments.domain.PaymentCommand command;
    try {
      command = parser.parse(body, type);
    } catch (ApiFailure failure) {
      // Date expiry must not prevent an original outcome from being replayed.
      // Other invalid fields still return every safe issue, including date-window failures.
      if (!failure.issues.isEmpty()
          && failure.issues.stream().allMatch(issue -> issue.code().equals("OUTSIDE_WINDOW")))
        command = parser.parse(body, type, false);
      else throw failure;
    }
    req.setAttribute("paymentId", command.paymentId());
    var result = service.transfer(command, auth.getName(), Wire.correlation(req));
    req.setAttribute("outcome", result.body().get("status"));
    req.setAttribute("errorCode", result.body().get("code"));
    var response = Wire.response(result.http(), result.body(), req);
    if (result.http() == 201)
      return ResponseEntity.status(201)
          .headers(response.getHeaders())
          .header("Location", "/api/v1/payments/" + command.paymentId())
          .body(response.getBody());
    return response;
  }

  @GetMapping("/payments/{id}")
  public ResponseEntity<String> find(
      @PathVariable String id, HttpServletRequest req, Authentication auth) {
    Wire.negotiate(req.getHeader("Accept"));
    var result = service.find(id, auth.getName());
    return Wire.response(result.http(), result.body(), req);
  }

  @GetMapping("/accounts/{id}/balance")
  public ResponseEntity<String> balance(
      @PathVariable String id, HttpServletRequest req, Authentication auth) {
    return Wire.response(200, service.balance(id, auth.getName()), req);
  }

  @GetMapping("/admin/reconciliation")
  public ResponseEntity<String> reconciliation(HttpServletRequest req) {
    return Wire.response(
        200,
        java.util.Map.of(
            "issues", service.reconcile(), "checkedAt", java.time.Instant.now().toString()),
        req);
  }

  @GetMapping(value = "/openapi", produces = "application/json")
  public org.springframework.core.io.Resource openapi() {
    return new org.springframework.core.io.ClassPathResource("static/openapi.json");
  }

  @GetMapping("/admin/retention")
  public ResponseEntity<String> retention(
      HttpServletRequest req, @Value("${payment.audit-retention-days:2555}") int days) {
    if (days < 1 || days > 36500)
      throw new IllegalStateException("Invalid retention configuration");
    return Wire.response(200, service.retention(days), req);
  }
}
