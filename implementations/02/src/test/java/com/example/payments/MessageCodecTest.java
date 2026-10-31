package com.example.payments;

import com.example.payments.api.MessageCodec;
import com.example.payments.domain.ApiProblem;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {
    final MessageCodec codec = new MessageCodec();
    String json(String amount) { return "{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":"+amount+",\"date\":\""+OffsetDateTime.now(ZoneOffset.UTC)+"\",\"purpose\":\" Invoice \"}"; }
    @Test void canonicalAmountAndFingerprintAreEquivalentAcrossFormats() {
        String j=json("125.750");
        var a=codec.parse(j.getBytes(),"application/json");
        String x="<payment><paymentId>"+a.paymentId()+"</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>"+a.date()+"</date><purpose>Invoice</purpose></payment>";
        var b=codec.parse(x.getBytes(),"application/xml");
        assertEquals("125.750",b.amount().toPlainString()); assertEquals(a.fingerprint(), b.fingerprint());
    }
    @Test void duplicateUnknownAndInvalidTypedFieldsAreMalformed() {
        for(String j:new String[]{json("1").replace("\"amount\":1", "\"amount\":1,\"amount\":2"),json("true"),json("1").replace("\"purpose\"", "\"unknown\""),json("[]")})
            assertEquals("MALFORMED_MESSAGE",assertThrows(ApiProblem.class,()->codec.parse(j.getBytes(),"application/json")).code);
    }
    @Test void collectsAllSafeValidationIssues() {
        var p=assertThrows(ApiProblem.class,()->codec.parse("{\"paymentId\":\"bad\",\"amount\":-0.1234}".getBytes(),"application/json"));
        assertEquals("VALIDATION_FAILED",p.code); assertTrue(p.issues.size()>=6); assertNull(p.paymentId);
    }
    @Test void rejectsPrecisionAndMissingOffsetWithoutRounding() {
        assertThrows(ApiProblem.class,()->codec.parse(json("1.0001").getBytes(),"application/json"));
        assertThrows(ApiProblem.class,()->codec.parse(json("1").replaceAll("[0-9]{4}-[0-9]{2}-[0-9]{2}T[^\"]+", "2026-10-28T10:00:00").getBytes(),"application/json"));
    }
    @Test void rejectsXxeDtdNestedDuplicateAndTrailingDocuments() {
        for(String xml:new String[]{"<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>","<payment><amount>1</amount><amount>2</amount></payment>","<payment><purpose><nested>x</nested></purpose></payment>","<payment/> <payment/>","<wrong/>"})
            assertEquals("MALFORMED_MESSAGE",assertThrows(ApiProblem.class,()->codec.parse(xml.getBytes(),"application/xml")).code);
        assertThrows(ApiProblem.class,()->codec.parse((json("1")+" {}").getBytes(),"application/json"));
    }
    @Test void externalEntitiesNeverMakeNetworkRequests() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/entity",exchange->{requests.incrementAndGet();byte[] secret="never read".getBytes();exchange.sendResponseHeaders(200,secret.length);exchange.getResponseBody().write(secret);exchange.close();});
        server.start();
        try {
            String payload="<!DOCTYPE payment [<!ENTITY x SYSTEM 'http://127.0.0.1:"+server.getAddress().getPort()+"/entity'>]><payment><purpose>&x;</purpose></payment>";
            assertEquals("MALFORMED_MESSAGE",assertThrows(ApiProblem.class,()->codec.parse(payload.getBytes(),"application/xml")).code);
            assertEquals(0,requests.get());
        } finally {server.stop(0);}
    }
    @Test void collectsTimeAndMaximumIssuesAlongsideMissingFields() {
        String input="{\"paymentId\":\"d92192df-f79a-4553-8120-5660d2eb1294\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":10000.001,\"date\":\""+OffsetDateTime.now(ZoneOffset.UTC).minusDays(40)+"\"}";
        var error=assertThrows(ApiProblem.class,()->codec.parse(input.getBytes(),"application/json"));
        assertEquals(java.util.Set.of("purpose","date","amount"),error.issues.stream().map(ApiProblem.Issue::field).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void doesNotNormalizeIdentifiersAndBoundsSize() {
        assertThrows(ApiProblem.class,()->codec.parse(json("1").replace("d92192df-f79a-4553-8120-5660d2eb1294", " d92192df-f79a-4553-8120-5660d2eb1294").getBytes(),"application/json"));
        assertEquals(413,assertThrows(ApiProblem.class,()->codec.parse(new byte[65537],"application/json")).http);
    }
}
