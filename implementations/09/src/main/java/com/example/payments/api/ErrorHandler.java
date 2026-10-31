package com.example.payments.api;

import com.example.payments.domain.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
  private final Responder responder;

  public ErrorHandler(Responder responder) {
    this.responder = responder;
  }

  @ExceptionHandler(ApiError.class)
  public ResponseEntity<byte[]> api(ApiError e, HttpServletRequest r) {
    try {
      return responder.response(e.http, e.body(Responder.correlation(r)), r);
    } catch (ApiError negotiation) {
      return responder.jsonError(negotiation, r);
    }
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<byte[]> database(DataAccessException e, HttpServletRequest r) {
    return api(new ApiError(503, "TEMPORARILY_UNAVAILABLE"), r);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> other(Exception e, HttpServletRequest r) {
    org.slf4j.LoggerFactory.getLogger(ErrorHandler.class)
        .atError()
        .addKeyValue("correlationId", Responder.correlation(r))
        .addKeyValue("errorClass", e.getClass().getSimpleName())
        .log("Unhandled request failure");
    return api(new ApiError(500, "INTERNAL_ERROR"), r);
  }
}
