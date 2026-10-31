package com.example.payments.api;

import com.example.payments.domain.ApiProblem;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import org.jboss.logging.Logger;
import java.util.UUID;

@Provider
public class ProblemMapper implements ExceptionMapper<Throwable> {
    @Inject Responses responses;
    @Inject MessageCodec codec;
    @Inject MeterRegistry metrics;
    @Context HttpHeaders headers;
    @Context jakarta.ws.rs.container.ContainerRequestContext request;
    private static final Logger LOG=Logger.getLogger(ProblemMapper.class);
    public Response toResponse(Throwable failure) {
        ApiProblem p=failure instanceof ApiProblem problem?problem:
                failure instanceof NotFoundException?new ApiProblem(404,"NOT_FOUND"):
                failure instanceof NotAllowedException?new ApiProblem(405,"METHOD_NOT_ALLOWED"):
                failure instanceof BadRequestException?new ApiProblem(400,"MALFORMED_MESSAGE"):new ApiProblem(500,"INTERNAL_ERROR");
        request.setProperty("errorCode",p.code);
        if(p.http==500){try {metrics.counter("payments.unexpected.failures").increment();}catch(RuntimeException ignored){} LOG.error("Unexpected payment processing failure; details withheld");}
        String correlation=(String)request.getProperty("correlationId");if(correlation==null)correlation=UUID.randomUUID().toString();
        boolean xml=false;
        try {xml=responses.xml(headers);}catch(RuntimeException ignored){p=new ApiProblem(406,"NOT_ACCEPTABLE");}
        var builder=Response.status(p.http).type(xml?"application/xml":"application/json").entity(codec.write(responses.error(p,correlation),xml)).header("X-Correlation-ID",correlation);
        if(p.http==401)builder.header("WWW-Authenticate","Bearer");
        if(p.http==429||p.http==503)builder.header("Retry-After","1");
        return builder.build();
    }
}
