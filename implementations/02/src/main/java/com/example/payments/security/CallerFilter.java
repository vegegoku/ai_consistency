package com.example.payments.security;

import com.example.payments.api.*;
import com.example.payments.domain.ApiProblem;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;
import java.util.concurrent.*;

@Provider @PreMatching @Priority(100)
public class CallerFilter implements ContainerRequestFilter {
    @Inject JWTParser parser;
    @ConfigProperty(name="payments.require-tls",defaultValue="true") boolean tls;
    @ConfigProperty(name="payments.rate-per-minute",defaultValue="600") int rate;
    @ConfigProperty(name="payments.rate-max-principals",defaultValue="10000") int maxPrincipals;
    private record Window(long minute,int count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    public void filter(ContainerRequestContext request) {
        if(!request.getUriInfo().getPath().replaceFirst("^/+", "").startsWith("api/"))return;
        if(tls&&!request.getUriInfo().getRequestUri().getScheme().equals("https"))throw new ApiProblem(400,"TLS_REQUIRED");
        String header=request.getHeaderString("Authorization");
        if(header==null||!header.regionMatches(true,0,"Bearer ",0,7)||header.length()>16384)throw new ApiProblem(401,"UNAUTHENTICATED");
        String subject,scopes;
        try {
            var token=parser.parse(header.substring(7));subject=token.getSubject();scopes=token.getClaim("scope");
            if(subject==null||subject.isBlank()||subject.length()>128||subject.indexOf(0)>=0||token.getExpirationTime()<=java.time.Instant.now().getEpochSecond())throw new IllegalArgumentException();
        }catch(Exception invalid){throw new ApiProblem(401,"UNAUTHENTICATED");}
        String path=request.getUriInfo().getPath().replaceFirst("^/+", "");
        String required=path.startsWith("api/v1/admin/")?"payments:admin":request.getMethod().equals("POST")?"payments:submit":"payments:read";
        if(scopes==null||!new HashSet<>(Arrays.asList(scopes.split(" +"))).contains(required))throw new ApiProblem(403,"FORBIDDEN");
        request.setProperty("principal",subject);
        long minute=System.currentTimeMillis()/60000;
        // Clean expired subjects rather than retaining an unbounded principal cache.
        if(windows.size()>=maxPrincipals)windows.entrySet().removeIf(e->e.getValue().minute<minute);
        if(!windows.containsKey(subject)&&windows.size()>=maxPrincipals)throw new ApiProblem(429,"RATE_LIMITED");
        Window w=windows.compute(subject,(k,old)->new Window(minute,old==null||old.minute!=minute?1:old.count+1));
        if(w.count>rate)throw new ApiProblem(429,"RATE_LIMITED");
    }
}
