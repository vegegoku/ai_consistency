package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.PaymentException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final MessageParser parser;
  private final PaymentService service;
  private final ApiErrors errors;
  private final int max, rate;
  private final MeterRegistry metrics;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  private record Bucket(long minute, int count) {}

  public PaymentController(
      MessageParser parser,
      PaymentService service,
      ApiErrors errors,
      @Value("${payments.max-body:65536}") int max,
      @Value("${payments.rate-per-minute:600}") int rate,
      MeterRegistry metrics) {
    this.parser = parser;
    this.service = service;
    this.errors = errors;
    this.max = max;
    this.rate = rate;
    this.metrics = metrics;
  }

  private void request(Authentication a, HttpServletRequest r) {
    errors.media(r);
    metrics.counter("payments.requests", "method", r.getMethod()).increment();
    long minute = System.currentTimeMillis() / 60000;
    if (buckets.size() > 10000) buckets.entrySet().removeIf(e -> e.getValue().minute < minute);
    if (buckets.size() > 20000 && !buckets.containsKey(a.getName()))
      throw new PaymentException(429, "RATE_LIMITED");
    var b =
        buckets.compute(
            a.getName(),
            (k, v) ->
                v == null || v.minute != minute
                    ? new Bucket(minute, 1)
                    : new Bucket(minute, v.count + 1));
    if (b.count > rate) throw new PaymentException(429, "RATE_LIMITED");
  }

  @PostMapping("/payments")
  public ResponseEntity<byte[]> submit(HttpServletRequest r, Authentication a) throws IOException {
    request(a, r);
    String type;
    try {
      type = MediaType.parseMediaType(r.getContentType()).toString().split(";")[0];
    } catch (Exception e) {
      throw new PaymentException(415, "UNSUPPORTED_MEDIA_TYPE");
    }
    if (!type.equals("application/json") && !type.equals("application/xml"))
      throw new PaymentException(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] body = r.getInputStream().readNBytes(max + 1);
    var command = parser.parse(body, type);
    var result = service.transfer(command, a.getName(), errors.cid(r));
    var response = errors.response(r, result.http(), result.body());
    return ResponseEntity.status(response.getStatusCode())
        .headers(response.getHeaders())
        .header("Location", "/api/v1/payments/" + command.paymentId())
        .body(response.getBody());
  }

  @GetMapping("/payments/{id}")
  public ResponseEntity<byte[]> get(
      @PathVariable String id, Authentication a, HttpServletRequest r) {
    request(a, r);
    return errors.response(r, 200, service.get(id, a.getName()));
  }

  @GetMapping("/accounts/{id}/balance")
  public ResponseEntity<byte[]> balance(
      @PathVariable String id, Authentication a, HttpServletRequest r) {
    request(a, r);
    return errors.response(r, 200, service.balance(id, a.getName()));
  }

  @GetMapping("/admin/reconciliation")
  public ResponseEntity<byte[]> reconcile(Authentication a, HttpServletRequest r) {
    request(a, r);
    return errors.response(r, 200, service.reconcile());
  }
}
