package com.example.payments.api;

import com.example.payments.domain.ApiFailure;
import com.example.payments.domain.PaymentCommand;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.xml.stream.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
public final class PaymentCodec {
    private static final Set<String> FIELDS=Set.of("paymentId","fromAccount","toAccount","amount","date","purpose","notes");
    private final Clock clock; private final BigDecimal maximum; private final long future, age; private final int maxBody;
    private final ObjectMapper json;
    public PaymentCodec(Clock clock, @Value("${payments.maximum:10000.000}") String maximum,
                        @Value("${payments.future-seconds:300}") long future,
                        @Value("${payments.age-seconds:2592000}") long age,
                        @Value("${payments.max-body-bytes:65536}") int maxBody) {
        this.clock=clock; this.maximum=new BigDecimal(maximum); this.future=future; this.age=age; this.maxBody=maxBody;
        json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(maxBody).maxNumberLength(64).build()).build());
        json.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        json.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public PaymentCommand parse(byte[] body, String mediaType) {return parse(body,mediaType,true);}
    public PaymentCommand parseForSubmission(byte[] body,String mediaType){return parse(body,mediaType,false);}
    private PaymentCommand parse(byte[] body,String mediaType,boolean checkWindow) {
        if (body==null || body.length==0) throw new ApiFailure(400,"MALFORMED_MESSAGE");
        if (body.length>maxBody) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
        Map<String,String> values;
        try {
            values=switch(mediaType) {
                case "application/json" -> parseJson(body);
                case "application/xml" -> parseXml(body);
                default -> throw new ApiFailure(415,"UNSUPPORTED_MEDIA_TYPE");
            };
        } catch (ApiFailure e) { throw e; }
        catch (Exception e) { throw new ApiFailure(400,"MALFORMED_MESSAGE"); }
        return validate(values,checkWindow);
    }
    private Map<String,String> parseJson(byte[] body) throws Exception {
        var values=new HashMap<String,String>();
        try(var parser=json.getFactory().createParser(body)) {
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new ApiFailure(400,"MALFORMED_MESSAGE");
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new ApiFailure(400,"MALFORMED_MESSAGE");
                String field=parser.currentName();JsonToken token=parser.nextToken();
                if(!FIELDS.contains(field) || (token!=JsonToken.VALUE_STRING && !(field.equals("amount") && (token==JsonToken.VALUE_NUMBER_FLOAT || token==JsonToken.VALUE_NUMBER_INT))))throw new ApiFailure(400,"MALFORMED_MESSAGE");
                values.put(field,parser.getText());
            }
            if(parser.nextToken()!=null)throw new ApiFailure(400,"MALFORMED_MESSAGE");
        }
        return values;
    }
    private Map<String,String> parseXml(byte[] body) throws Exception {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicID,systemID,baseURI,namespace)->{throw new XMLStreamException("External resources prohibited");});
        var reader=factory.createXMLStreamReader(new ByteArrayInputStream(body));
        var values=new HashMap<String,String>(); int depth=0; boolean rootSeen=false; String field=null; StringBuilder text=null;
        try {
            while(reader.hasNext()) {
                int event=reader.next();
                if (event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE || event==XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException("Prohibited XML event");
                if (event==XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (reader.getAttributeCount()!=0 || reader.getNamespaceCount()!=0 || (reader.getNamespaceURI()!=null && !reader.getNamespaceURI().isEmpty())) throw new XMLStreamException("Attributes/namespaces prohibited");
                    if (depth==1) { if(rootSeen || !reader.getLocalName().equals("payment")) throw new XMLStreamException("Invalid root"); rootSeen=true; }
                    else if(depth==2) {field=reader.getLocalName();if(!FIELDS.contains(field)||values.containsKey(field)) throw new XMLStreamException("Unknown/duplicate field");text=new StringBuilder();}
                    else throw new XMLStreamException("Nested element");
                } else if(event==XMLStreamConstants.CHARACTERS || event==XMLStreamConstants.CDATA) {
                    if(depth==2) text.append(reader.getText()); else if(!reader.isWhiteSpace()) throw new XMLStreamException("Unexpected text");
                } else if(event==XMLStreamConstants.END_ELEMENT) {
                    if(depth==2) values.put(field,text.toString());depth--;
                }
            }
            if(!rootSeen || depth!=0) throw new XMLStreamException("Missing root");
            return values;
        } finally {reader.close();}
    }
    private PaymentCommand validate(Map<String,String> v,boolean checkWindow) {
        var issues=new ArrayList<ApiFailure.Issue>(); UUID id=null; BigDecimal amount=null; Instant date=null; boolean outsideWindow=false;
        for(String field:List.of("paymentId","fromAccount","toAccount","amount","date","purpose"))
            if(!v.containsKey(field)||v.get(field).isBlank()) issue(issues,field,"REQUIRED","This field is required.");
        try {String raw=v.get("paymentId"); if(raw!=null && raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) id=UUID.fromString(raw); else throw new IllegalArgumentException();}
        catch(Exception e){issue(issues,"paymentId","INVALID_UUID","A UUID is required.");}
        for(String f:List.of("fromAccount","toAccount")) {String a=v.get(f);if(a!=null && (!a.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) issue(issues,f,"INVALID_ACCOUNT","Invalid account identifier.");}
        if(v.get("fromAccount")!=null && v.get("fromAccount").equals(v.get("toAccount"))) issue(issues,"toAccount","SAME_ACCOUNT","Accounts must differ.");
        try {
            String raw=v.get("amount");if(raw==null||!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new IllegalArgumentException();
            amount=new BigDecimal(raw);
            if(amount.signum()<=0 || amount.scale()>3 || amount.compareTo(maximum)>0) throw new IllegalArgumentException();
            amount=amount.setScale(3);
        } catch(Exception e){issue(issues,"amount","INVALID_AMOUNT","Amount must be positive, at most three decimals, and within the payment maximum.");}
        try {
            date=OffsetDateTime.parse(v.get("date")).toInstant(); Instant now=clock.instant();
            outsideWindow=date.isAfter(now.plusSeconds(future)) || date.isBefore(now.minusSeconds(age));
        } catch(Exception e){issue(issues,"date","INVALID_DATE","An offset timestamp within the configured instruction window is required.");}
        String purpose=v.get("purpose")==null?null:v.get("purpose").trim();
        String notes=v.get("notes")==null?null:v.get("notes").trim();
        if(purpose!=null && purpose.length()>255) issue(issues,"purpose","TOO_LONG","Maximum 255 characters.");
        if(notes!=null && notes.length()>1000) issue(issues,"notes","TOO_LONG","Maximum 1000 characters.");
        if(outsideWindow && (checkWindow || !issues.isEmpty()))issue(issues,"date","INVALID_DATE","An offset timestamp within the configured instruction window is required.");
        if(!issues.isEmpty()) throw new ApiFailure(400,"VALIDATION_FAILED",id,issues,null,null);
        return new PaymentCommand(id,v.get("fromAccount"),v.get("toAccount"),amount,date,purpose,notes);
    }
    private static void issue(List<ApiFailure.Issue> list,String field,String code,String message){list.add(new ApiFailure.Issue(field,code,message));}
}
