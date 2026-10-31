package com.example.payments.security;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  JwtDecoder decoder(
      @Value("${payments.jwt.jwk-set-uri}") String jwks,
      @Value("${payments.jwt.issuer}") String issuer,
      @Value("${payments.jwt.audience}") String audience) {
    var d = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            token ->
                token.getAudience().contains(audience)
                        && token.getExpiresAt() != null
                        && token.getSubject() != null
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error(
                            "invalid_token",
                            "Token audience, subject or expiry is invalid",
                            null))));
    return d;
  }

  @Bean
  SecurityFilterChain chain(
      HttpSecurity h,
      ResponseWriter writer,
      @Value("${payments.require-tls:true}") boolean tls,
      @Value("${payments.rate-per-minute:600}") int rate,
      @Value("${payments.max-body-bytes:65536}") int max)
      throws Exception {
    h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/openapi.yaml")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasAuthority("SCOPE_payments:admin")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (q, s, e) -> {
                          s.setHeader("WWW-Authenticate", "Bearer");
                          writer.writeError(
                              q,
                              s,
                              new ApiFailure(
                                  401, "UNAUTHENTICATED", "Authentication is required."));
                        }))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, s, x) -> {
                          s.setHeader("WWW-Authenticate", "Bearer");
                          writer.writeError(
                              q,
                              s,
                              new ApiFailure(
                                  401, "UNAUTHENTICATED", "Authentication is required."));
                        })
                    .accessDeniedHandler(
                        (q, s, x) ->
                            writer.writeError(
                                q,
                                s,
                                new ApiFailure(
                                    403, "FORBIDDEN", "The operation is not permitted."))));
    h.addFilterAfter(
        new OperationalFilter(writer, tls, rate, max), BearerTokenAuthenticationFilter.class);
    return h.build();
  }
}
