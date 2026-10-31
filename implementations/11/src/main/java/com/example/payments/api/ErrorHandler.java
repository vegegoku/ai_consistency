package com.example.payments.api;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.ApiFailure;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(ApiFailure.class)
  public ResponseEntity<String> known(ApiFailure e, HttpServletRequest req) {
    req.setAttribute("errorCode", e.code);
    if (e.paymentId != null) req.setAttribute("paymentId", e.paymentId);
    var body =
        new java.util.LinkedHashMap<>(
            PaymentService.error(e.http, e.code, e.paymentId, Wire.correlation(req)).body());
    body.put("issues", e.issues);
    if (e.http == 406)
      return ResponseEntity.status(406)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Wire.encode(body, MediaType.APPLICATION_JSON));
    var response = Wire.response(e.http, body, req);
    if (e.http == 503)
      return ResponseEntity.status(503)
          .headers(response.getHeaders())
          .header("Retry-After", "1")
          .body(response.getBody());
    return response;
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<String> database(Exception e, HttpServletRequest req) {
    return known(new ApiFailure(503, "TEMPORARILY_UNAVAILABLE"), req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<String> unknown(Exception e, HttpServletRequest req) {
    return known(new ApiFailure(500, "INTERNAL_ERROR"), req);
  }
}
