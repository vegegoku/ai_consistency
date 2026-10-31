package com.example.payments.observability;
import com.example.payments.api.ResponseWriter;
import com.example.payments.domain.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.UUID;
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(RequestFilter.class);
    private final ResponseWriter writer;private final MeterRegistry metrics;private final boolean tls,trust;private final int body;
    public RequestFilter(ResponseWriter writer,MeterRegistry metrics,boolean tls,boolean trust,int body){this.writer=writer;this.metrics=metrics;this.tls=tls;this.trust=trust;this.body=body;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,java.io.IOException {
        String supplied=req.getHeader("X-Correlation-ID");String correlation=trust&&supplied!=null&&supplied.matches("[A-Za-z0-9-]{1,64}")?supplied:UUID.randomUUID().toString();
        req.setAttribute("correlationId",correlation);res.setHeader("X-Correlation-ID",correlation);res.setHeader("Cache-Control","no-store");long start=System.nanoTime();
        try(MDC.MDCCloseable ignored=MDC.putCloseable("correlationId",correlation)){
            if(tls && req.getRequestURI().startsWith("/api/")&&!req.isSecure()){writer.writeError(new ApiFailure(403,"HTTPS_REQUIRED"),req,res);return;}
            if(req.getContentLengthLong()>body){writer.writeError(new ApiFailure(413,"PAYLOAD_TOO_LARGE"),req,res);return;}
            chain.doFilter(req,res);
        }finally{
            long millis=(System.nanoTime()-start)/1000000;
            metrics.counter("payments.http.requests","status",String.valueOf(res.getStatus())).increment();
            LOG.atInfo().addKeyValue("correlationId",correlation).addKeyValue("paymentId",req.getAttribute("paymentId")).addKeyValue("principal",req.getAttribute("principal")).addKeyValue("status",res.getStatus()).addKeyValue("code",req.getAttribute("errorCode")).addKeyValue("latencyMs",millis).log("Payment API request finished");
        }
    }
}
