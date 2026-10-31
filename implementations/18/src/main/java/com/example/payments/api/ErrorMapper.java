package com.example.payments.api;
import com.example.payments.domain.*;
import com.example.payments.security.Caller;
import com.example.payments.observability.PaymentTelemetry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.*;
import java.time.Instant;
import java.util.*;

@Provider
public class ErrorMapper implements ExceptionMapper<Throwable> {
    @Inject Caller caller; @Inject ResponseCodec codec; @Inject PaymentTelemetry telemetry;
    @Context HttpHeaders headers;
    public static Map<String,Object> error(UUID id,String status,String code,List<Issue> issues,String correlation,Instant timestamp){
        Map<String,Object> map=new LinkedHashMap<>();if(id!=null)map.put("paymentId",id.toString());map.put("status",status);map.put("code",code);
        map.put("message",code.equals("TEMPORARILY_UNAVAILABLE")?"The outcome is not confirmed. Query or retry using the same paymentId.":"The payment could not be completed.");
        map.put("issues",issues);map.put("correlationId",correlation);map.put("timestamp",timestamp.toString());return map;
    }
    @Override public Response toResponse(Throwable throwable){
        PaymentException error;
        if(throwable instanceof PaymentException e)error=e;
        else if(throwable instanceof WebApplicationException e){int status=e.getResponse().getStatus();error=new PaymentException(status,switch(status){case 401->"UNAUTHENTICATED";case 403->"FORBIDDEN";case 404->"NOT_FOUND";case 406->"NOT_ACCEPTABLE";case 413,400->"MALFORMED_MESSAGE";case 415->"UNSUPPORTED_MEDIA_TYPE";default->"INTERNAL_ERROR";});}
        else error=new PaymentException(500,"INTERNAL_ERROR");
        telemetry.failure(error.code());String media="application/json";try{media=codec.negotiate(headers.getAcceptableMediaTypes());}catch(Exception ignored){}
        var response=codec.response(error.http(),error(error.paymentId(),"ERROR",error.code(),error.issues(),caller.correlationId,Instant.now()),media,"error");
        if(error.http()==401)response.header("WWW-Authenticate","Bearer");if(error.http()==503||error.http()==429)response.header("Retry-After",error.http()==429?60:1);
        return response.build();
    }
}
