package com.example.payments.api;

import com.example.payments.application.*;
import com.example.payments.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final PaymentService service;
  private final MessageParser parser;
  private final Responses responses;
  private final int maxBody;

  public PaymentController(
      PaymentService s,
      MessageParser p,
      Responses r,
      @Value("${payments.max-body:65536}") int maxBody) {
    this.service = s;
    this.parser = p;
    this.responses = r;
    this.maxBody = maxBody;
  }

  @PostMapping("/payments")
  @PreAuthorize("hasAuthority('SCOPE_payments:write')")
  public ResponseEntity<byte[]> submit(HttpServletRequest request, Authentication authentication)
      throws java.io.IOException {
    boolean useXml = responses.wantsXml(request);
    String ct = request.getContentType();
    MediaType type;
    try {
      type = MediaType.parseMediaType(ct == null ? "application/octet-stream" : ct);
    } catch (Exception e) {
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    }
    boolean xml = type.getType().equals("application") && type.getSubtype().equals("xml"),
        json = type.getType().equals("application") && type.getSubtype().equals("json");
    if (!xml && !json) throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    if (type.getCharset() != null
        && !type.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
    byte[] bytes = request.getInputStream().readNBytes(maxBody + 1);
    if (bytes.length > maxBody) throw new ApiException(413, "MESSAGE_TOO_LARGE");
    if (bytes.length == 0) throw new ApiException(400, "MALFORMED_MESSAGE");
    var command = parser.parse(bytes, xml);
    var result = service.transfer(command, authentication.getName());
    return responses.response(result.http(), result.body(), useXml);
  }

  @GetMapping("/payments/{id}")
  @PreAuthorize("hasAuthority('SCOPE_payments:read')")
  public ResponseEntity<byte[]> get(
      @PathVariable String id, Authentication a, HttpServletRequest r) {
    responses.wantsXml(r);
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (Exception e) {
      throw new ApiException(404, "PAYMENT_NOT_FOUND");
    }
    return responses.response(200, service.find(uuid, a.getName()), r);
  }

  @GetMapping("/accounts/{id}/balance")
  @PreAuthorize("hasAuthority('SCOPE_payments:read')")
  public ResponseEntity<byte[]> balance(
      @PathVariable String id, Authentication a, HttpServletRequest r) {
    responses.wantsXml(r);
    return responses.response(200, service.balance(id, a.getName()), r);
  }
}
