package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;

@Component
public class ResponseWriter {
    private final ObjectMapper json;
    public ResponseWriter(ObjectMapper json){this.json=json;}
    public String format(String accept){
        if(accept==null || accept.isBlank())return "application/json";
        try {
            var media=MediaType.parseMediaTypes(accept);
            String selected=null;double best=-1;
            for(var candidate:List.of(MediaType.APPLICATION_JSON,MediaType.APPLICATION_XML)) {
                int specificity=-1;double quality=0;
                for(var range:media) {
                    if(!range.isCompatibleWith(candidate))continue;
                    int current=range.isWildcardType()?0:range.isWildcardSubtype()?1:2;
                    if(current>specificity){specificity=current;quality=range.getQualityValue();}
                }
                if(quality>0 && quality>best){selected=candidate.toString();best=quality;}
            }
            if(selected!=null)return selected;
        } catch(IllegalArgumentException ignored) {}
        throw new ApiFailure(406,"NOT_ACCEPTABLE");
    }
    public ResponseEntity<String> response(int http,Map<String,?> body,String format,String root){
        return ResponseEntity.status(http).contentType(MediaType.parseMediaType(format)).body(encode(body,format,root));
    }
    public Map<String,Object> errorBody(ApiFailure e,HttpServletRequest request){
        var body=new LinkedHashMap<String,Object>();if(e.paymentId()!=null)body.put("paymentId",e.paymentId().toString());
        body.put("status","ERROR");body.put("code",e.code());body.put("message",e.getMessage());body.put("issues",e.issues());
        body.put("correlationId",e.correlationId()!=null?e.correlationId():request.getAttribute("correlationId"));body.put("timestamp",(e.timestamp()!=null?e.timestamp():Instant.now()).toString());return body;
    }
    public void writeError(ApiFailure e,HttpServletRequest request,HttpServletResponse response) throws java.io.IOException {
        String media="application/json";try{media=format(request.getHeader("Accept"));}catch(ApiFailure ignored){}
        response.setStatus(e.http());response.setContentType(media);response.setCharacterEncoding("UTF-8");
        request.setAttribute("errorCode",e.code());
        if(e.http()==503||e.http()==429)response.setHeader("Retry-After","2");
        response.getWriter().write(encode(errorBody(e,request),media,"error"));
    }
    public String encode(Map<String,?> body,String format,String root){
        try {
            if(format.equals("application/json"))return json.writeValueAsString(body);
            StringWriter text=new StringWriter();XMLStreamWriter xml=XMLOutputFactory.newFactory().createXMLStreamWriter(text);xml.writeStartDocument("UTF-8","1.0");xml.writeStartElement(root);
            for(var entry:body.entrySet())element(xml,entry.getKey(),entry.getValue());
            xml.writeEndElement();xml.writeEndDocument();xml.close();return text.toString();
        }catch(Exception e){throw new IllegalStateException("Response encoding failed",e);}
    }
    private void element(XMLStreamWriter xml,String name,Object value) throws Exception {
        xml.writeStartElement(name);
        if(value instanceof Map<?,?> map){for(var e:map.entrySet())element(xml,e.getKey().toString(),e.getValue());}
        else if(value instanceof Iterable<?> list){for(Object item:list){if(item instanceof ApiFailure.Issue issue)element(xml,"issue",Map.of("field",issue.field(),"code",issue.code(),"message",issue.message()));else element(xml,"issue",item);}}
        else if(value!=null)xml.writeCharacters(value.toString());
        xml.writeEndElement();
    }
}
