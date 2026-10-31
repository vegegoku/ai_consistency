package com.example.payments.api;

import com.example.payments.domain.ApiProblem;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.MIMEHeader;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Reject known oversized bodies before Quarkus's lower-level, empty-body 413 response. */
@ApplicationScoped
public class BodyLimitHandler {
    @Inject MessageCodec codec;
    @Inject Responses responses;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;
    private static final org.jboss.logging.Logger LOG=org.jboss.logging.Logger.getLogger(BodyLimitHandler.class);
    @ConfigProperty(name="payments.max-body-bytes",defaultValue="65536") long maxBody;
    void install(@Observes Router router) {
        router.route("/api/*").order(-100).handler(context->{
            long started=System.nanoTime();
            String length=context.request().getHeader("Content-Length");
            long bytes;
            try {bytes=length==null?0:Long.parseLong(length);}catch(NumberFormatException malformed){context.next();return;}
            if(bytes<=maxBody){context.next();return;}
            String id=UUID.randomUUID().toString();
            boolean xml=false, supported=context.parsedHeaders().accept().isEmpty();
            var accepts=new ArrayList<>(context.parsedHeaders().accept());
            accepts.sort(Comparator.comparingDouble(MIMEHeader::weight).reversed());
            for(var accept:accepts) {
                if(accept.weight()<=0)continue;
                if(accept.component().equals("*")||accept.component().equals("application")&&(accept.subComponent().equals("*")||accept.subComponent().equals("json"))){supported=true;break;}
                if(accept.component().equals("application")&&accept.subComponent().equals("xml")){supported=true;xml=true;break;}
            }
            ApiProblem problem=new ApiProblem(supported?413:406,supported?"PAYLOAD_TOO_LARGE":"NOT_ACCEPTABLE");
            try {
                org.jboss.logging.MDC.put("correlationId",id);org.jboss.logging.MDC.put("status",problem.http);org.jboss.logging.MDC.put("errorCode",problem.code);org.jboss.logging.MDC.put("latencyMs",(System.nanoTime()-started)/1000000);
                LOG.info("payment_api_request");metrics.counter("payments.requests","status",String.valueOf(problem.http)).increment();
            } catch(RuntimeException ignored){} finally {for(String field:new String[]{"correlationId","status","errorCode","latencyMs"})org.jboss.logging.MDC.remove(field);}
            context.response().setStatusCode(problem.http).putHeader("Content-Type",xml?"application/xml":"application/json").putHeader("X-Correlation-ID",id).putHeader("Connection","close")
                    .end(Buffer.buffer(codec.write(responses.error(problem,id),xml)));
        });
    }
}
