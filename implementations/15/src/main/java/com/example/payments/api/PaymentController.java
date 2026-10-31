package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.PaymentException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService service;
  private final MessageParser parser;
  private final ResponseWriter writer;
  private final int maxBytes;

  public PaymentController(
      PaymentService service,
      MessageParser parser,
      ResponseWriter writer,
      @Value("${payments.max-body-bytes}") int maxBytes) {
    this.service = service;
    this.parser = parser;
    this.writer = writer;
    this.maxBytes = maxBytes;
  }

  @PostMapping("/payments")
  public ResponseEntity<String> submit(HttpServletRequest request, Authentication auth)
      throws IOException {
    writer.negotiate(request.getHeader("Accept"));
    byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
    com.example.payments.domain.PaymentCommand command;
    try {
      command = parser.parse(body, request.getContentType());
    } catch (PaymentException validation) {
      if (validation.code().equals("VALIDATION_FAILED")
          && !validation.issues().isEmpty()
          && validation.issues().stream()
              .allMatch(
                  issue ->
                      Set.of("DATE_OUT_OF_RANGE", "PAYMENT_LIMIT_EXCEEDED")
                          .contains(issue.code()))) {
        var candidate = parser.parseReplayCandidate(body, request.getContentType());
        var replay = service.replayIfStored(candidate, auth.getName());
        if (replay.isPresent())
          return writer.response(replay.get().httpStatus(), replay.get().body(), request);
      }
      throw validation;
    }
    var outcome =
        service.transfer(command, auth.getName(), request.getAttribute("correlationId").toString());
    var response = writer.response(outcome.httpStatus(), outcome.body(), request);
    return outcome.httpStatus() == 201
        ? ResponseEntity.created(URI.create("/api/v1/payments/" + command.paymentId()))
            .headers(response.getHeaders())
            .body(response.getBody())
        : response;
  }

  @GetMapping("/payments/{paymentId}")
  public ResponseEntity<String> payment(
      @PathVariable String paymentId, HttpServletRequest request, Authentication auth) {
    writer.negotiate(request.getHeader("Accept"));
    UUID id;
    try {
      id = UUID.fromString(paymentId);
      if (!id.toString().equals(paymentId)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new PaymentException(404, "PAYMENT_NOT_FOUND", "The payment was not found.");
    }
    return writer.response(200, service.payment(id, auth.getName()), request);
  }

  @GetMapping("/accounts/{accountId}/balance")
  public ResponseEntity<String> balance(
      @PathVariable String accountId, HttpServletRequest request, Authentication auth) {
    writer.negotiate(request.getHeader("Accept"));
    return writer.response(200, service.balance(accountId, auth.getName()), request);
  }

  @GetMapping("/admin/reconciliation")
  public ResponseEntity<String> reconcile(HttpServletRequest request) {
    return writer.response(200, service.reconcile(), request);
  }
}
