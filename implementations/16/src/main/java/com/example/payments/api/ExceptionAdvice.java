package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ExceptionAdvice {
  private final ResponseWriter writer;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public ExceptionAdvice(
      ResponseWriter writer, io.micrometer.core.instrument.MeterRegistry metrics) {
    this.writer = writer;
    this.metrics = metrics;
  }

  @ExceptionHandler(ApiFailure.class)
  public void known(ApiFailure e, HttpServletRequest q, HttpServletResponse s) throws IOException {
    writer.writeError(q, s, e);
  }

  @ExceptionHandler(DataAccessException.class)
  public void database(DataAccessException e, HttpServletRequest q, HttpServletResponse s)
      throws IOException {
    writer.writeError(
        q,
        s,
        new ApiFailure(
            503,
            "TEMPORARILY_UNAVAILABLE",
            "Please query status or retry with the same paymentId."));
  }

  @ExceptionHandler(Exception.class)
  public void unexpected(Exception e, HttpServletRequest q, HttpServletResponse s)
      throws IOException {
    metrics.counter("payments.unexpected_failures").increment();
    writer.writeError(
        q,
        s,
        new ApiFailure(
            500,
            "INTERNAL_ERROR",
            "An unexpected processing error occurred; query status before retrying."));
  }
}
