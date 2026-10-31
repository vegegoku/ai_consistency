package com.example.payments;

import com.example.payments.api.MessageParser;
import com.example.payments.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    final MessageParser parser = new MessageParser();
    final Instant now = Instant.parse("2026-10-28T10:30:00Z");
    String json(String amount) { return """
        {"paymentId":"d92192df-f79a-4553-8120-5660d2eb1294","fromAccount":"A","toAccount":"B","amount":%s,"date":"2026-10-28T13:30:00+03:00","purpose":" Invoice ","notes":"  ok  "}
        """.formatted(amount); }
    PaymentCommand parse(String text, String media) { return parser.parse(text.getBytes(StandardCharsets.UTF_8), media, now, "10000.000", 300, 30, 65536); }
    @Test void acceptsExactDecimalAndCanonicalizesOnlyDocumentedFields() {
        var c = parse(json("125.750"), "application/json");
        assertEquals("125.750", c.amount().toPlainString()); assertEquals("Invoice", c.purpose()); assertEquals("ok", c.notes());
    }
    @Test void jsonXmlFingerprintEqual() {
        String xml="<payment><paymentId>d92192df-f79a-4553-8120-5660d2eb1294</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>Invoice</purpose><notes>ok</notes></payment>";
        assertEquals(parse(json("125.750"),"application/json").fingerprint(),parse(xml,"application/xml").fingerprint());
    }
    @Test void rejectsDuplicateUnknownAndTypedValues() {
        for(String s:new String[]{json("1").replace("\"fromAccount\":\"A\"","\"fromAccount\":\"A\",\"fromAccount\":\"X\""),json("true"),json("1").replace("\"A\"","123"),json("1").replace("\"notes\"","\"unknown\"")})
            assertEquals("MALFORMED_MESSAGE",assertThrows(PaymentException.class,()->parse(s,"application/json")).code());
    }
    @Test void collectsValidationWithoutRounding() {
        var e=assertThrows(PaymentException.class,()->parse(json("1.0001").replace("\"B\"","\"A\"").replace(" Invoice "," "),"application/json"));
        assertEquals("VALIDATION_FAILED",e.code()); assertEquals(3,e.issues().size());
    }
    @Test void rejectsUnsafeXmlAndRepeatedElements() {
        for(String x:new String[]{"<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>","<payment><amount>1</amount><amount>2</amount></payment>","<payment xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='file:///etc/passwd'/></payment>"})
            assertEquals("MALFORMED_MESSAGE",assertThrows(PaymentException.class,()->parse(x,"application/xml")).code());
    }
    @Test void timestampOffsetAndCanonicalUuidRequired() {
        assertThrows(PaymentException.class,()->parse(json("1").replace("2026-10-28T13:30:00+03:00","2026-10-28T13:30:00"),"application/json"));
        assertThrows(PaymentException.class,()->parse(json("1").replace("d92192df-f79a-4553-8120-5660d2eb1294","1-1-1-1-1"),"application/json"));
    }
    @Test void rejectsExcessiveBodyDepthAndExponent() {
        assertThrows(PaymentException.class,()->parse(json("1e999999"),"application/json"));
        assertThrows(PaymentException.class,()->parse("[".repeat(1000)+"]".repeat(1000),"application/json"));
        assertThrows(PaymentException.class,()->parse("x".repeat(65537),"application/xml"));
    }
}
