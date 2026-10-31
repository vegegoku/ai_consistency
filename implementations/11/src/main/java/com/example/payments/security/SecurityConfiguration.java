package com.example.payments.security;

import com.example.payments.api.Wire;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
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
      @Value("${security.issuer}") String issuer,
      @Value("${security.jwk-set-uri}") String jwks,
      @Value("${security.audience}") String audience) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid audience", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            audienceValidator,
            jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                        && jwt.getSubject().length() <= 128
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, @Value("${payment.rate-per-minute:120}") int rate)
      throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**", "/api/v1/admin/**")
                    .hasAuthority("SCOPE_payments:admin")
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                    .hasAuthority("SCOPE_payments:write")
                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                    .hasAuthority("SCOPE_payments:read")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (req, res, e) -> Wire.error(req, res, 401, "UNAUTHENTICATED")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, x) -> Wire.error(req, res, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler((req, res, x) -> Wire.error(req, res, 403, "FORBIDDEN")))
        .addFilterAfter(new RateLimitFilter(rate), BearerTokenAuthenticationFilter.class)
        .build();
  }
}
