package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
  private final MessageParser parser;
  private final PaymentService service;
  private final ResponseWriter writer;
  private final ReconciliationService reconciliation;
  private final int size;

  public PaymentController(
      MessageParser parser,
      PaymentService service,
      ResponseWriter writer,
      ReconciliationService reconciliation,
      @Value("${payments.max-body-bytes:65536}") int size) {
    this.parser = parser;
    this.service = service;
    this.writer = writer;
    this.reconciliation = reconciliation;
    this.size = size;
  }

  @PostMapping("/api/v1/payments")
  public ResponseEntity<byte[]> submit(HttpServletRequest request, JwtAuthenticationToken auth)
      throws IOException {
    writer.negotiate(request);
    String content = request.getContentType();
    String type = content == null ? "" : content.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!Set.of("application/json", "application/xml").contains(type))
      throw new ApiFailure(
          415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json or application/xml.");
    var command = parser.parse(request.getInputStream().readNBytes(size + 1), type);
    request.setAttribute("paymentId", command.paymentId().toString());
    var result =
        service.transfer(
            command, Caller.from(auth), (String) request.getAttribute("correlationId"));
    request.setAttribute("errorCode", result.body().getOrDefault("code", ""));
    request.setAttribute("principal", auth.getName());
    var response = writer.response(result.http(), result.body(), request);
    if (result.http() == 201)
      return ResponseEntity.status(201)
          .headers(response.getHeaders())
          .header("Location", "/api/v1/payments/" + command.paymentId())
          .body(response.getBody());
    return response;
  }

  @GetMapping("/api/v1/payments/{id}")
  public ResponseEntity<byte[]> get(
      @PathVariable String id, HttpServletRequest request, JwtAuthenticationToken auth) {
    writer.negotiate(request);
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
      if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new ApiFailure(404, "PAYMENT_NOT_FOUND", "Payment was not found.");
    }
    var result = service.get(uuid, Caller.from(auth));
    return writer.response(result.http(), result.body(), request);
  }

  @GetMapping("/api/v1/accounts/{id}/balance")
  public ResponseEntity<byte[]> balance(
      @PathVariable String id, HttpServletRequest request, JwtAuthenticationToken auth) {
    writer.negotiate(request);
    return writer.response(200, service.balance(id, Caller.from(auth)), request);
  }

  @GetMapping("/api/v1/admin/reconciliation")
  public ResponseEntity<byte[]> reconciliation(
      HttpServletRequest request, JwtAuthenticationToken auth) {
    if (!Caller.from(auth).admin())
      throw new ApiFailure(403, "FORBIDDEN", "The operation is not permitted.");
    return writer.response(200, Map.of("issues", reconciliation.report()), request);
  }
}
