package com.example.payments.api;

import com.example.payments.domain.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
  private final Responses responses;
  private final MeterRegistry metrics;

  public ErrorHandler(Responses r, MeterRegistry m) {
    responses = r;
    metrics = m;
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> handle(Exception exception, HttpServletRequest r) {
    ApiException e =
        exception instanceof ApiException a
            ? a
            : exception instanceof AccessDeniedException
                ? new ApiException(403, "FORBIDDEN")
                : exception instanceof DataAccessResourceFailureException
                        || exception instanceof TransientDataAccessException
                        || exception instanceof org.springframework.transaction.TransactionException
                    ? new ApiException(503, "TEMPORARILY_UNAVAILABLE")
                    : new ApiException(500, "INTERNAL_ERROR");
    metrics.counter("payments.errors", "code", e.code).increment();
    org.slf4j.LoggerFactory.getLogger(ErrorHandler.class)
        .atWarn()
        .addKeyValue("code", e.code)
        .addKeyValue("paymentId", e.paymentId)
        .log("request failed");
    boolean xml = false;
    try {
      xml = responses.wantsXml(r);
    } catch (ApiException ignored) {
    }
    return responses.response(e.http, responses.error(e), xml);
  }
}
