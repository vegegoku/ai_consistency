package com.example.payments.api;

import com.example.payments.domain.ApiException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;

public final class AuthenticationErrors {
  private AuthenticationErrors() {}

  @Provider
  public static class Missing
      implements ExceptionMapper<io.quarkus.security.UnauthorizedException> {
    @Inject Responses responses;
    @Context Request request;

    public Response toResponse(io.quarkus.security.UnauthorizedException e) {
      return responses.error(
          new ApiException(401, "UNAUTHENTICATED", "A valid bearer token is required."),
          responses.errorMedia(request));
    }
  }

  @Provider
  public static class Invalid
      implements ExceptionMapper<io.quarkus.security.AuthenticationFailedException> {
    @Inject Responses responses;
    @Context Request request;

    public Response toResponse(io.quarkus.security.AuthenticationFailedException e) {
      return responses.error(
          new ApiException(401, "UNAUTHENTICATED", "A valid bearer token is required."),
          responses.errorMedia(request));
    }
  }

  @Provider
  public static class Forbidden implements ExceptionMapper<io.quarkus.security.ForbiddenException> {
    @Inject Responses responses;
    @Context Request request;

    public Response toResponse(io.quarkus.security.ForbiddenException e) {
      return responses.error(
          new ApiException(403, "FORBIDDEN", "The operation is not permitted."),
          responses.errorMedia(request));
    }
  }
}
