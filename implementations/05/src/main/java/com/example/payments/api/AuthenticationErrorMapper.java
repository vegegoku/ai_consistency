package com.example.payments.api;

import io.quarkus.security.AuthenticationFailedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Override Quarkus's specific built-in mapper so invalid bearer tokens use the API contract. */
@Provider
public class AuthenticationErrorMapper implements ExceptionMapper<AuthenticationFailedException> {
  @Inject ErrorMapper errors;

  public Response toResponse(AuthenticationFailedException error) {
    return errors.toResponse(error);
  }
}
