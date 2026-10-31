package com.example.payments.api;

import io.quarkus.security.AuthenticationFailedException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;

@Provider
@Priority(1)
public class AuthenticationFailureMapper implements ExceptionMapper<AuthenticationFailedException> {
  @Inject FailureMapper mapper;

  public Response toResponse(AuthenticationFailedException e) {
    return Response.fromResponse(mapper.toResponse(e)).header("WWW-Authenticate", "Bearer").build();
  }
}
