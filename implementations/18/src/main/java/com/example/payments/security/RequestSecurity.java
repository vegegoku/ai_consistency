package com.example.payments.security;

import com.example.payments.domain.PaymentException;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Provider @Priority(Priorities.AUTHENTICATION)
public class RequestSecurity implements ContainerRequestFilter {
    @Inject JWTParser jwtParser; @Inject Caller caller; @Inject DataSource db;
    @ConfigProperty(name="payments.rate-per-minute") int rate;
    @ConfigProperty(name="payments.trust-correlation-header",defaultValue="false") boolean trustCorrelation;
    @Override public void filter(ContainerRequestContext request){
        if(!request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/v1/"))return;
        if(trustCorrelation)try{caller.correlationId=UUID.fromString(request.getHeaderString("X-Correlation-ID")).toString();}catch(Exception ignored){}
        MDC.put("correlationId",caller.correlationId);
        String authorization=request.getHeaderString("Authorization");
        if(authorization==null||!authorization.startsWith("Bearer "))throw new PaymentException(401,"UNAUTHENTICATED");
        Set<String> scopes;
        try {
            var jwt=jwtParser.parse(authorization.substring(7));caller.principal=jwt.getSubject();
            if(caller.principal==null||caller.principal.isBlank()||caller.principal.length()>128)throw new IllegalArgumentException();
            Object scope=jwt.getClaim("scope");scopes=new HashSet<>(Arrays.asList(scope==null?new String[0]:scope.toString().split(" ")));
        }catch(Exception e){throw new PaymentException(401,"UNAUTHENTICATED");}
        String permission=request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/v1/admin/")?"payments:admin":request.getMethod().equals("POST")?"payments:write":"payments:read";
        if(!scopes.contains(permission))throw new PaymentException(403,"FORBIDDEN");
        limit();
    }
    private void limit(){
        try(var c=db.getConnection();var s=c.prepareStatement("INSERT INTO rate_buckets(principal,bucket,requests) VALUES(?,floor(extract(epoch from clock_timestamp())/60)::bigint,1) ON CONFLICT(principal) DO UPDATE SET requests=CASE WHEN rate_buckets.bucket=EXCLUDED.bucket THEN rate_buckets.requests+1 ELSE 1 END,bucket=EXCLUDED.bucket RETURNING requests")){
            s.setQueryTimeout(3);s.setString(1,caller.principal);try(var r=s.executeQuery()){r.next();if(r.getInt(1)>rate)throw new PaymentException(429,"RATE_LIMITED");}
        }catch(SQLException e){throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE");}
    }
}
