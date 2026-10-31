package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final MessageCodec codec;
  private final PaymentService service;
  private final Responder respond;

  public PaymentController(MessageCodec codec, PaymentService service, Responder respond) {
    this.codec = codec;
    this.service = service;
    this.respond = respond;
  }

  @PostMapping("/payments")
  public ResponseEntity<byte[]> transfer(HttpServletRequest request, Authentication auth)
      throws IOException {
    respond.checkAccept(request);
    var c = codec.read(request.getInputStream(), request.getContentType());
    var result = service.transfer(c, auth.getName(), Responder.correlation(request));
    return respond.response(result.http(), result.body(), request);
  }

  @GetMapping("/payments/{id}")
  public ResponseEntity<byte[]> find(
      @PathVariable String id, HttpServletRequest r, Authentication auth) {
    try {
      return respond.response(200, service.find(UUID.fromString(id), auth.getName()), r);
    } catch (IllegalArgumentException e) {
      throw new ApiError(404, "PAYMENT_NOT_FOUND");
    }
  }

  @GetMapping("/accounts/{id}/balance")
  public ResponseEntity<byte[]> balance(
      @PathVariable String id, HttpServletRequest r, Authentication auth) {
    return respond.response(200, service.balance(id, auth.getName()), r);
  }

  @GetMapping("/admin/reconciliation")
  public ResponseEntity<byte[]> reconcile(HttpServletRequest r) {
    return respond.response(200, service.reconcile(), r);
  }
}
