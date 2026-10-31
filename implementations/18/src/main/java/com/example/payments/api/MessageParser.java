package com.example.payments.api;

import com.example.payments.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import javax.xml.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class MessageParser {
    static final Set<String> FIELDS=Set.of("paymentId","fromAccount","toAccount","amount","date","purpose","notes");
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxNumberLength(64).maxStringLength(65536).build()).build())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PaymentCommand parse(byte[] body,String media,Instant now,String maximum,long futureSeconds,long oldDays,int bodyLimit) {
        if(body.length==0 || body.length>bodyLimit) throw new PaymentException(400,"MALFORMED_MESSAGE");
        Map<String,String> values;
        try { values=media.equals("application/json")?readJson(body):readXml(body); }
        catch(Exception e) { throw new PaymentException(400,"MALFORMED_MESSAGE"); }
        List<Issue> issues=new ArrayList<>();
        UUID id=null; BigDecimal amount=null; Instant date=null;
        String rawId=values.get("paymentId");
        try { id=UUID.fromString(rawId); if(!id.toString().equals(rawId))throw new IllegalArgumentException(); }
        catch(Exception e){issue(issues,"paymentId","A canonical UUID is required.");}
        String from=values.get("fromAccount"),to=values.get("toAccount");
        for(String field:List.of("fromAccount","toAccount")){String v=values.get(field);if(v==null||v.isBlank()||v.length()>128)issue(issues,field,"An account identifier of 1 to 128 characters is required.");}
        if(from!=null&&from.equals(to))issue(issues,"toAccount","Source and destination must differ.");
        try {
            String raw=values.get("amount"); if(raw==null||raw.length()>64)throw new IllegalArgumentException();
            amount=new BigDecimal(raw);
            if(amount.scale()>3 || amount.scale() < -20 || amount.precision()>19 || amount.signum()<=0 || amount.compareTo(new BigDecimal(maximum))>0)throw new IllegalArgumentException();
            amount=amount.setScale(3);
        } catch(Exception e){issue(issues,"amount","Amount must be positive, within the configured maximum, with at most 3 fractional digits.");}
        try { date=OffsetDateTime.parse(values.get("date")).toInstant();if(date.isAfter(now.plusSeconds(futureSeconds))||date.isBefore(now.minus(Duration.ofDays(oldDays))))throw new IllegalArgumentException(); }
        catch(Exception e){issue(issues,"date","An offset timestamp within the configured age and future window is required.");}
        String purpose=values.get("purpose"),notes=values.get("notes");
        purpose=purpose==null?null:purpose.strip(); notes=notes==null?"":notes.strip();
        if(purpose==null||purpose.isBlank()||purpose.length()>255)issue(issues,"purpose","Purpose is required and must be at most 255 characters.");
        if(notes.length()>1000)issue(issues,"notes","Notes must be at most 1000 characters.");
        if(!issues.isEmpty())throw new PaymentException(400,"VALIDATION_FAILED",id,issues);
        return new PaymentCommand(id,from,to,amount,date,purpose,notes);
    }
    private void issue(List<Issue> issues,String field,String message){issues.add(new Issue(field,"INVALID_VALUE",message));}
    private Map<String,String> readJson(byte[] body)throws IOException {
        JsonNode root=json.readTree(body); if(root==null||!root.isObject())throw new IOException();
        Map<String,String> values=new HashMap<>();
        var fields=root.fields();while(fields.hasNext()){
            var f=fields.next();if(!FIELDS.contains(f.getKey()))throw new IOException();
            JsonNode v=f.getValue();if(v.isNull()){values.put(f.getKey(),null);continue;}
            if(!v.isTextual() && !(f.getKey().equals("amount")&&v.isNumber()))throw new IOException();
            // Preserve numeric lexical precision: tree decimals otherwise discard trailing zeros.
            values.put(f.getKey(),v.asText());
        }
        // A second streaming pass retains exact amount scale, including zero fractional digits.
        try(JsonParser p=json.getFactory().createParser(body)){
            while(p.nextToken()!=null)if(p.currentToken()==JsonToken.FIELD_NAME&&p.currentName().equals("amount")){
                p.nextToken();if(p.currentToken().isNumeric())values.put("amount",p.getText());
            }
        }
        return values;
    }
    private Map<String,String> readXml(byte[] body)throws XMLStreamException {
        XMLInputFactory factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External resources prohibited");});
        XMLStreamReader r=factory.createXMLStreamReader(new ByteArrayInputStream(body));
        Map<String,String> values=new HashMap<>();int depth=0;boolean seen=false;String current=null;StringBuilder value=new StringBuilder();
        try {while(r.hasNext()){
            int event=r.next();
            if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE||event==XMLStreamConstants.PROCESSING_INSTRUCTION)throw new XMLStreamException();
            if(event==XMLStreamConstants.START_ELEMENT){
                depth++;if(depth>2||r.getAttributeCount()>0||r.getNamespaceCount()>0||!r.getNamespaceURI().isEmpty())throw new XMLStreamException();
                String name=r.getLocalName();
                if(depth==1){if(seen||!name.equals("payment"))throw new XMLStreamException();seen=true;}
                else {if(!FIELDS.contains(name)||values.containsKey(name))throw new XMLStreamException();current=name;value.setLength(0);}
            } else if(event==XMLStreamConstants.CHARACTERS||event==XMLStreamConstants.CDATA){
                if(depth==2)value.append(r.getText());else if(!r.isWhiteSpace())throw new XMLStreamException();
            } else if(event==XMLStreamConstants.END_ELEMENT){if(depth==2)values.put(current,value.toString());depth--;}
        }}finally{r.close();}
        if(!seen||depth!=0)throw new XMLStreamException();return values;
    }
}
