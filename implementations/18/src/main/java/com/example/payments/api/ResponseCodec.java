package com.example.payments.api;
import com.example.payments.domain.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.*;
import java.util.*;

@ApplicationScoped
public class ResponseCodec {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    final XmlMapper xml=(XmlMapper)new XmlMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    public String negotiate(List<MediaType> acceptable){
        for(var media:acceptable){
            try{if(Double.parseDouble(media.getParameters().getOrDefault("q","1"))==0)continue;}catch(NumberFormatException e){continue;}
            if(media.isCompatible(MediaType.APPLICATION_JSON_TYPE))return "application/json";
            if(media.isCompatible(MediaType.APPLICATION_XML_TYPE))return "application/xml";
        }
        throw new PaymentException(406,"NOT_ACCEPTABLE");
    }
    public Response.ResponseBuilder response(int http,Object object,String media,String root){
        try {String body=media.equals("application/xml")?xml.writer().withRootName(root).writeValueAsString(object):json.writeValueAsString(object);return Response.status(http).type(media).entity(body);}
        catch(Exception e){throw new IllegalStateException("Response serialization failed",e);}
    }
    public Map<String,Object> receipt(StoredPayment payment){
        Map<String,Object> map=new LinkedHashMap<>();map.put("paymentId",payment.paymentId().toString());map.put("status",payment.status());map.put("fromAccount",payment.fromAccount());map.put("toAccount",payment.toAccount());map.put("amount",payment.amount());map.put("currency",payment.currency());map.put("processedAt",payment.processedAt().toString());
        if(payment.rejectionCode()!=null)map.put("rejectionCode",payment.rejectionCode());return map;
    }
}
