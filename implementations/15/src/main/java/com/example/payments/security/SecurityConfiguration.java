package com.example.payments.security;

import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.PaymentException;
import com.example.payments.observability.BoundaryFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
      @Value("${payments.jwt.issuer}") String issuer,
      @Value("${payments.jwt.jwk-set-uri}") String jwks,
      @Value("${payments.jwt.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience().contains(audience)
                    && jwt.getExpiresAt() != null
                    && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                        "invalid_token", "Required audience, expiry or subject is missing", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }

  @Bean
  FilterRegistrationBean<BoundaryFilter> boundary(
      ResponseWriter writer,
      @Value("${payments.require-https}") boolean https,
      @Value("${payments.max-body-bytes}") int max) {
    var bean = new FilterRegistrationBean<>(new BoundaryFilter(writer, https, max));
    bean.setOrder(-200);
    return bean;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, ResponseWriter writer, @Value("${payments.rate-limit}") int rate)
      throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/actuator/health/**", "/openapi.yaml")
                .permitAll()
                .requestMatchers("/actuator/prometheus", "/api/v1/admin/**")
                .hasAuthority("SCOPE_payments:admin")
                .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                .hasAuthority("SCOPE_payments:write")
                .requestMatchers(HttpMethod.GET, "/api/v1/payments/**")
                .hasAuthority("SCOPE_payments:read")
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/**")
                .hasAuthority("SCOPE_accounts:read")
                .anyRequest()
                .denyAll());
    http.oauth2ResourceServer(
        o ->
            o.jwt(j -> {})
                .authenticationEntryPoint(
                    (req, res, e) ->
                        writer.error(
                            req,
                            res,
                            new PaymentException(
                                401, "UNAUTHENTICATED", "A valid bearer token is required."))));
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, error) ->
                        writer.error(
                            req,
                            res,
                            new PaymentException(
                                401, "UNAUTHENTICATED", "A valid bearer token is required.")))
                .accessDeniedHandler(
                    (req, res, error) ->
                        writer.error(
                            req,
                            res,
                            new PaymentException(
                                403, "FORBIDDEN", "The caller lacks the required permission."))));
    http.addFilterAfter(
        new PrincipalRateLimitFilter(rate, writer), BearerTokenAuthenticationFilter.class);
    return http.build();
  }
}
