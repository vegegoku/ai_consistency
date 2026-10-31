package com.example.payments.api;

import io.quarkus.security.AuthenticationFailedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;

@Provider
public class AuthenticationFailureMapper implements ExceptionMapper<AuthenticationFailedException> {
  @Inject FailureMapper failures;

  public Response toResponse(AuthenticationFailedException exception) {
    return failures.toResponse(exception);
  }
}
