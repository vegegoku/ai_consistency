package com.example.payments;
import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import com.example.payments.observability.RequestFilter;
import com.example.payments.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {
    String token(KeyPair key,String issuer,String audience,Instant expiry) throws Exception {
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),new JWTClaimsSet.Builder().issuer(issuer).subject("alice").audience(audience).expirationTime(Date.from(expiry)).issueTime(new Date()).claim("scope","payments:read payments:write").build());
        jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
    }
    @Test void validatesSignatureIssuerAudienceAndExpiration() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build();decoder.setJwtValidator(SecurityConfiguration.validators("https://issuer.example","payment-service"));
        assertEquals("alice",decoder.decode(token(key,"https://issuer.example","payment-service",Instant.now().plusSeconds(300))).getSubject());
        for(String invalid:List.of(token(key,"wrong","payment-service",Instant.now().plusSeconds(300)),token(key,"https://issuer.example","wrong",Instant.now().plusSeconds(300)),token(key,"https://issuer.example","payment-service",Instant.now().minusSeconds(300)),token(generator.generateKeyPair(),"https://issuer.example","payment-service",Instant.now().plusSeconds(300))))assertThrows(JwtException.class,()->decoder.decode(invalid));
    }
    @Test void rejectsInsecureTransportAndIgnoresUntrustedCorrelation() throws Exception {
        var writer=new ResponseWriter(new ObjectMapper());var filter=new RequestFilter(writer,new SimpleMeterRegistry(),true,false,65536);var request=new MockHttpServletRequest("POST","/api/v1/payments");request.addHeader("X-Correlation-ID","attacker");var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter.doFilter(request,response,(r,s)->called.set(true));assertFalse(called.get());assertEquals(403,response.getStatus());assertNotEquals("attacker",response.getHeader("X-Correlation-ID"));assertTrue(response.getContentAsString().contains("HTTPS_REQUIRED"));
    }
    @Test void rateLimitIsPerAuthenticatedPrincipal() throws Exception {
        var filter=new RateLimitFilter(1,new ResponseWriter(new ObjectMapper()));
        try{
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice","ignored",List.of()));
            var first=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST","/api/v1/payments"),first,(r,s)->{});assertEquals(200,first.getStatus());
            var second=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST","/api/v1/payments"),second,(r,s)->fail());assertEquals(429,second.getStatus());assertEquals("2",second.getHeader("Retry-After"));
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test void acceptQualityAndXmlEscaping(){
        var writer=new ResponseWriter(new ObjectMapper());assertEquals("application/xml",writer.format("application/json;q=0.2,application/xml;q=0.9"));assertThrows(ApiFailure.class,()->writer.format("application/json;q=0"));assertEquals("application/xml",writer.format("application/json;q=0,*/*;q=1"));assertEquals("application/xml",writer.format("application/json;q=0.1,application/*;q=0.9"));
        String xml=writer.encode(Map.of("message","<&>"),"application/xml","error");assertTrue(xml.contains("&lt;&amp;&gt;"));
    }
}
