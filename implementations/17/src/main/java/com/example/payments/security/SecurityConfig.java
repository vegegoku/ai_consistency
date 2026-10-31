package com.example.payments.security;

import com.example.payments.api.ApiErrors;
import com.example.payments.application.PaymentService;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
      @org.springframework.beans.factory.annotation.Value(
              "${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
          String issuer,
      @org.springframework.beans.factory.annotation.Value(
              "${spring.security.oauth2.resourceserver.jwt.audiences}")
          String audience) {
    return new org.springframework.security.oauth2.jwt.SupplierJwtDecoder(
        () -> {
          org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
              org.springframework.security.oauth2.jwt.JwtDecoders.fromIssuerLocation(issuer);
          var required =
              new org.springframework.security.oauth2.core.OAuth2TokenValidator<
                  org.springframework.security.oauth2.jwt.Jwt>() {
                public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(
                    org.springframework.security.oauth2.jwt.Jwt token) {
                  return token.getExpiresAt() != null
                          && token.getSubject() != null
                          && !token.getSubject().isBlank()
                          && token.getSubject().length() <= 128
                      ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
                          .success()
                      : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                          new org.springframework.security.oauth2.core.OAuth2Error(
                              "invalid_token", "Required claims are missing", null));
                }
              };
          decoder.setJwtValidator(
              new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                  org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(
                      issuer),
                  new org.springframework.security.oauth2.jwt.JwtClaimValidator<
                      java.util.List<String>>("aud", v -> v != null && v.contains(audience)),
                  required));
          return decoder;
        });
  }

  @Bean
  PaymentService.FaultInjector faultInjector() {
    return () -> {};
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ApiErrors errors) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus", "/api/v1/admin/**")
                    .hasAuthority("SCOPE_payments:admin")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/payments")
                    .hasAuthority("SCOPE_payments:write")
                    .requestMatchers("/api/v1/payments/**")
                    .hasAuthority("SCOPE_payments:read")
                    .requestMatchers("/api/v1/accounts/**")
                    .hasAuthority("SCOPE_accounts:read")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (r, s, e) -> errors.write(r, s, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler((r, s, e) -> errors.write(r, s, 403, "FORBIDDEN")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((r, s, x) -> errors.write(r, s, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler((r, s, x) -> errors.write(r, s, 403, "FORBIDDEN")))
        .build();
  }
}
