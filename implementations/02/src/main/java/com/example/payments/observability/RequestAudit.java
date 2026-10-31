package com.example.payments.observability;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;

@Provider @PreMatching @Priority(50)
public class RequestAudit implements ContainerRequestFilter,ContainerResponseFilter {
    private static final Logger LOG=Logger.getLogger(RequestAudit.class);
    @Inject MeterRegistry metrics;
    @ConfigProperty(name="payments.trust-correlation-header",defaultValue="false") boolean trustHeader;
    public void filter(ContainerRequestContext request) {
        String id=request.getHeaderString("X-Correlation-ID");
        if(!trustHeader||id==null||!id.matches("[0-9a-fA-F-]{36}"))id=UUID.randomUUID().toString();
        try {id=UUID.fromString(id).toString();}catch(IllegalArgumentException bad){id=UUID.randomUUID().toString();}
        request.setProperty("correlationId",id);request.setProperty("started",System.nanoTime());
    }
    public void filter(ContainerRequestContext request,ContainerResponseContext response) {
        String id=(String)request.getProperty("correlationId");response.getHeaders().putSingle("X-Correlation-ID",id==null?UUID.randomUUID().toString():id);
        if(!request.getUriInfo().getPath().replaceFirst("^/+", "").startsWith("api/"))return;
        long elapsed=request.getProperty("started") instanceof Long s?System.nanoTime()-s:0;
        try {
            MDC.put("correlationId",id);MDC.put("paymentId",String.valueOf(request.getProperty("paymentId")));MDC.put("status",response.getStatus());MDC.put("errorCode",String.valueOf(request.getProperty("errorCode")));MDC.put("latencyMs",elapsed/1000000);
            LOG.info("payment_api_request");metrics.counter("payments.requests","status",String.valueOf(response.getStatus())).increment();
        }catch(RuntimeException ignored){}finally {for(String key:new String[]{"correlationId","paymentId","status","errorCode","latencyMs"})MDC.remove(key);}
    }
}
