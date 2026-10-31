package com.example.payments.api;

import com.example.payments.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class Responses {
    @Inject MessageCodec codec;
    public boolean xml(HttpHeaders headers) {
        for(MediaType media:headers.getAcceptableMediaTypes()) {
            String q=media.getParameters().get("q");if(q!=null&&Double.parseDouble(q)==0)continue;
            if(media.isCompatible(MediaType.APPLICATION_JSON_TYPE))return false;
            if(media.isCompatible(MediaType.APPLICATION_XML_TYPE))return true;
        }
        throw new ApiProblem(406,"NOT_ACCEPTABLE");
    }
    public Response send(int status,Object body,HttpHeaders headers) {
        boolean xml=xml(headers);
        return Response.status(status).type(xml?"application/xml":"application/json").entity(codec.write(body,xml)).build();
    }
    public Map<String,Object> error(ApiProblem error,String correlation) {
        var body=new LinkedHashMap<String,Object>();
        if(error.paymentId!=null)body.put("paymentId",error.paymentId);
        body.put("status",error.http==409||error.http==403&&error.paymentId!=null?"REJECTED":"ERROR");
        body.put("code",error.code);body.put("message",error.http==503?"The outcome may be queried or retried using the same payment ID.":"The payment could not be completed.");
        body.put("issues",error.issues);body.put("correlationId",correlation);body.put("timestamp",Instant.now().toString());return body;
    }
}
