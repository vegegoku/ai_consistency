package com.example.payments.api;

import com.example.payments.domain.*;
import com.example.payments.domain.ApiProblem.Issue;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import javax.xml.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class MessageCodec {
    static final Set<String> FIELDS=Set.of("paymentId","fromAccount","toAccount","amount","date","purpose","notes");
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(65536).maxNumberLength(100).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final XmlMapper xml=(XmlMapper)new XmlMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    @ConfigProperty(name="payments.max-body-bytes",defaultValue="65536") int maxBody=65536;
    @ConfigProperty(name="payments.max-amount",defaultValue="10000.000") BigDecimal maxAmount=new BigDecimal("10000.000");
    @ConfigProperty(name="payments.max-age-days",defaultValue="30") long maxAgeDays=30;
    @ConfigProperty(name="payments.future-seconds",defaultValue="300") long futureSeconds=300;

    public byte[] readBody(InputStream stream) {
        try {
            byte[] bytes=stream.readNBytes(maxBody+1);
            if(bytes.length>maxBody) throw new ApiProblem(413,"PAYLOAD_TOO_LARGE");
            return bytes;
        } catch(IOException e) { throw new ApiProblem(400,"MALFORMED_MESSAGE"); }
    }
    public PaymentCommand parse(byte[] bytes,String media) {
        if(bytes.length>maxBody) throw new ApiProblem(413,"PAYLOAD_TOO_LARGE");
        if(!Set.of("application/json","application/xml").contains(media)) throw new ApiProblem(415,"UNSUPPORTED_MEDIA_TYPE");
        if(bytes.length==0) throw new ApiProblem(400,"MALFORMED_MESSAGE");
        Map<String,String> values;
        try { values=media.equals("application/json")?readJson(bytes):readXml(bytes); }
        catch(Exception e) { throw new ApiProblem(400,"MALFORMED_MESSAGE"); }
        return validate(values);
    }
    Map<String,String> readJson(byte[] bytes) throws IOException {
        JsonNode root=json.readTree(bytes);
        if(root==null || !root.isObject()) throw new IOException();
        Map<String,String> result=new HashMap<>();
        var names=root.fieldNames();
        while(names.hasNext()) {
            String name=names.next(); JsonNode n=root.get(name);
            if(!FIELDS.contains(name)) throw new IOException();
            if(n.isNull()) { result.put(name,null); continue; }
            if(!n.isTextual() && !(name.equals("amount")&&n.isNumber())) throw new IOException();
            // Numeric values are decimal-only; a second token pass preserves lexical scale.
            result.put(name,n.asText());
        }
        // Parse amounts using token text rather than a binary floating-point tree node.
        try(JsonParser parser=json.getFactory().createParser(bytes)) {
            while(parser.nextToken()!=null) if(parser.currentToken()==JsonToken.FIELD_NAME && parser.currentName().equals("amount")) {
                parser.nextToken(); if(parser.currentToken().isNumeric()) result.put("amount",parser.getText());
            }
        }
        return result;
    }
    Map<String,String> readXml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory f=XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES,false);
        f.setXMLResolver((a,b,c,d)->{throw new XMLStreamException("External access denied");});
        XMLStreamReader r=f.createXMLStreamReader(new ByteArrayInputStream(bytes));
        Map<String,String> result=new HashMap<>(); int depth=0; String field=null; StringBuilder text=new StringBuilder(); boolean seen=false;
        try {
            while(r.hasNext()) {
                int event=r.next();
                if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE || event==XMLStreamConstants.PROCESSING_INSTRUCTION) throw new XMLStreamException();
                if(event==XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if(r.getAttributeCount()!=0 || r.getNamespaceCount()!=0 || (r.getNamespaceURI()!=null&&!r.getNamespaceURI().isEmpty())) throw new XMLStreamException();
                    if(depth==1) { if(seen||!r.getLocalName().equals("payment")) throw new XMLStreamException(); seen=true; }
                    else if(depth==2) {
                        field=r.getLocalName(); if(!FIELDS.contains(field)||result.containsKey(field)) throw new XMLStreamException(); text.setLength(0);
                    } else throw new XMLStreamException();
                } else if(event==XMLStreamConstants.CHARACTERS || event==XMLStreamConstants.CDATA) {
                    if(depth==2) { text.append(r.getText()); if(text.length()>maxBody) throw new XMLStreamException(); }
                    else if(!r.isWhiteSpace()) throw new XMLStreamException();
                } else if(event==XMLStreamConstants.END_ELEMENT) {
                    if(depth==2) result.put(field,text.toString()); depth--;
                }
            }
            if(!seen||depth!=0) throw new XMLStreamException();
            return result;
        } finally { r.close(); }
    }
    PaymentCommand validate(Map<String,String> v) {
        List<Issue> issues=new ArrayList<>();
        String id=v.get("paymentId"), from=v.get("fromAccount"), to=v.get("toAccount"), purpose=v.get("purpose"), notes=v.get("notes");
        String validId=null;
        if(id!=null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) validId=id;
        else issue(issues,"paymentId","A UUID is required.");
        for(String name:List.of("fromAccount","toAccount")) {
            String value=v.get(name);
            if(value==null||value.isBlank()||value.length()>128) issue(issues,name,"A nonblank account identifier of at most 128 characters is required.");
        }
        if(from!=null&&from.equals(to)) issue(issues,"toAccount","Source and destination must differ.");
        if(purpose==null||purpose.isBlank()||purpose.length()>255) issue(issues,"purpose","Purpose is required and must be at most 255 characters.");
        if(notes!=null&&notes.length()>1000) issue(issues,"notes","Notes must be at most 1000 characters.");
        BigDecimal amount=null;
        try {
            String raw=v.get("amount"); if(raw==null||!raw.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException();
            amount=new BigDecimal(raw);
            if(amount.signum()<=0||amount.scale()>3||amount.precision()-amount.scale()>16) throw new IllegalArgumentException();
            amount=amount.setScale(3);
        } catch(RuntimeException bad) { issue(issues,"amount","Amount must be positive, have at most 3 fractional digits, and not exceed the configured maximum."); }
        Instant date=null;
        try {
            date=OffsetDateTime.parse(v.get("date")).toInstant();
            // Time-window checks apply only to fresh IDs in the transactional use case; historical retries remain valid.
        } catch(RuntimeException bad) { issue(issues,"date","An offset timestamp within the configured instruction window is required."); }
        for(String field:FIELDS)if(v.get(field)!=null&&v.get(field).indexOf('\0')>=0)issue(issues,field,"NUL characters are not permitted.");
        if(!issues.isEmpty()) {
            if(amount!=null&&amount.compareTo(maxAmount)>0&&issues.stream().noneMatch(i->i.field().equals("amount")))issue(issues,"amount","Amount exceeds the configured maximum.");
            Instant now=Instant.now();
            if(date!=null&&(date.isAfter(now.plusSeconds(futureSeconds))||date.isBefore(now.minus(Duration.ofDays(maxAgeDays)))))issue(issues,"date","Instruction is outside the configured time window.");
            throw new ApiProblem(400,"VALIDATION_FAILED",validId,issues);
        }
        return new PaymentCommand(id,from,to,amount,date,purpose.trim(),notes==null?"":notes.trim());
    }
    private static void issue(List<Issue> issues,String field,String message) { issues.add(new Issue(field,"VALIDATION_FAILED",message)); }
    public byte[] write(Object value,boolean xmlResponse) {
        try { return xmlResponse?xml.writer().withRootName("response").writeValueAsBytes(value):json.writeValueAsBytes(value); }
        catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
}
