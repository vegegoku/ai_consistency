package com.example.payments.security;
import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import com.example.payments.observability.RequestFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${payments.jwt.jwk-set-uri}") String jwks,@Value("${payments.jwt.issuer}") String issuer,@Value("${payments.jwt.audience}") String audience){
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new org.springframework.boot.web.client.RestTemplateBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).readTimeout(java.time.Duration.ofSeconds(2)).build()).build();decoder.setJwtValidator(validators(issuer,audience));return decoder;
    }
    public static OAuth2TokenValidator<Jwt> validators(String issuer,String audience){
        OAuth2TokenValidator<Jwt> claims=jwt->jwt.getAudience().contains(audience)&&jwt.getExpiresAt()!=null&&jwt.getSubject()!=null&&!jwt.getSubject().isBlank()&&jwt.getSubject().length()<=128?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required claims are invalid",null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims);
    }
    @Bean SecurityFilterChain security(HttpSecurity http,ResponseWriter writer,MeterRegistry metrics,
        @Value("${payments.require-https:true}") boolean tls,@Value("${payments.trust-correlation-header:false}") boolean trust,
        @Value("${payments.max-body-bytes:65536}") int body,@Value("${payments.rate-per-minute:6000}") int rate)throws Exception {
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(a->a.requestMatchers("/actuator/health/**","/openapi.yaml").permitAll()
            .requestMatchers("/actuator/prometheus","/api/v1/operations/**").hasAuthority("SCOPE_payments:admin")
            .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/payments").hasAuthority("SCOPE_payments:write")
            .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/**").hasAuthority("SCOPE_payments:read").anyRequest().denyAll());
        http.oauth2ResourceServer(o->o.jwt(Customizer.withDefaults()).authenticationEntryPoint((req,res,e)->writer.writeError(new ApiFailure(401,"UNAUTHENTICATED"),req,res)));
        http.exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->writer.writeError(new ApiFailure(401,"UNAUTHENTICATED"),req,res)).accessDeniedHandler((req,res,error)->writer.writeError(new ApiFailure(403,"FORBIDDEN"),req,res)));
        http.addFilterBefore(new RequestFilter(writer,metrics,tls,trust,body),SecurityContextHolderFilter.class);
        http.addFilterAfter(new RateLimitFilter(rate,writer),BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
