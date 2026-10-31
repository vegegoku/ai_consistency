package com.example.payments.api;

import com.example.payments.domain.PaymentException;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ExceptionAdvice {
  private final ResponseWriter writer;

  public ExceptionAdvice(ResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(PaymentException.class)
  void payment(PaymentException e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    writer.error(req, res, e);
  }

  @ExceptionHandler(DataAccessException.class)
  void database(DataAccessException e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    writer.error(
        req,
        res,
        new PaymentException(
            503, "TEMPORARILY_UNAVAILABLE", "Query or retry using the same payment ID."));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  void method(Exception e, HttpServletRequest req, HttpServletResponse res) throws IOException {
    writer.error(
        req,
        res,
        new PaymentException(405, "METHOD_NOT_ALLOWED", "The HTTP method is not supported."));
  }

  @ExceptionHandler(Exception.class)
  void unexpected(Exception e, HttpServletRequest req, HttpServletResponse res) throws IOException {
    writer.error(
        req,
        res,
        new PaymentException(
            500,
            "INTERNAL_ERROR",
            "An unexpected error occurred; query or retry using the same payment ID."));
  }
}
