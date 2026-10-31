package com.example.payments.observability;
import com.example.payments.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import java.util.concurrent.TimeUnit;

@Provider
public class ResponseAudit implements ContainerResponseFilter {
    @Inject Caller caller; @Inject MeterRegistry metrics;
    private static final Logger LOG=Logger.getLogger(ResponseAudit.class);
    @Override public void filter(ContainerRequestContext request,ContainerResponseContext response){
        if(!request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("api/v1/"))return;
        response.getHeaders().putSingle("X-Correlation-ID",caller.correlationId);
        response.getHeaders().putSingle("Cache-Control","no-store");response.getHeaders().putSingle("X-Content-Type-Options","nosniff");
        try{MDC.put("correlationId",caller.correlationId);metrics.counter("payments_http_requests","method",request.getMethod(),"status",String.valueOf(response.getStatus())).increment();
            LOG.infof("API request completed status=%d latencyMs=%d",response.getStatus(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-caller.started));
        }finally{MDC.clear();}
    }
}
