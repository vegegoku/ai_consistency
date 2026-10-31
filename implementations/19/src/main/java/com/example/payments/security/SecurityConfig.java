package com.example.payments.security;

import com.example.payments.api.Responses;
import com.example.payments.domain.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain chain(HttpSecurity http, Responses responses) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/**", "/api/v1/operations/**")
                    .hasAuthority("SCOPE_payments:admin")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (r, s, e) ->
                            responses.write(r, s, new ApiException(401, "UNAUTHENTICATED"))))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (r, s, x) ->
                            responses.write(r, s, new ApiException(401, "UNAUTHENTICATED")))
                    .accessDeniedHandler(
                        (r, s, x) -> responses.write(r, s, new ApiException(403, "FORBIDDEN"))))
        .build();
  }

  @Bean
  public JwtDecoder jwtDecoder(
      @Value("${payments.jwt.jwks-uri}") String uri,
      @Value("${payments.jwt.issuer}") String issuer,
      @Value("${payments.jwt.audience}") String audience) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(uri).build();
    OAuth2TokenValidator<Jwt> aud =
        jwt ->
            jwt.getAudience().contains(audience)
                    && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank()
                    && jwt.getSubject().length() <= 200
                    && jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
    return decoder;
  }
}
