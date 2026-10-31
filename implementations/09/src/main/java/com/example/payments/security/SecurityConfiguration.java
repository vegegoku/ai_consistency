package com.example.payments.security;

import com.example.payments.api.Responder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
      @Value("${payments.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    OAuth2TokenValidator<Jwt> claims =
        jwt -> {
          boolean valid =
              jwt.getAudience().contains(audience)
                  && jwt.getExpiresAt() != null
                  && jwt.getSubject() != null
                  && !jwt.getSubject().isBlank()
                  && jwt.getSubject().length() <= 128;
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claims));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, Responder responder, @Value("${payments.rate-limit}") int limit)
      throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/openapi.yaml")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus", "/api/v1/admin/**")
                    .hasAuthority("SCOPE_payments:admin")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/payments")
                    .hasAuthority("SCOPE_payments:write")
                    .requestMatchers("/api/v1/accounts/**")
                    .hasAuthority("SCOPE_accounts:read")
                    .requestMatchers("/api/v1/payments/**")
                    .hasAuthority("SCOPE_payments:read")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (r, s, e) -> responder.error(r, s, 401, "UNAUTHENTICATED")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (r, s, x) -> responder.error(r, s, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler((r, s, x) -> responder.error(r, s, 403, "FORBIDDEN")));
    http.addFilterAfter(
        new RateLimitFilter(responder, limit), BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
