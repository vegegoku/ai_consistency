package com.example.payments;

import com.example.payments.api.PaymentCodec;
import com.example.payments.domain.ApiFailure;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class PaymentCodecTest {
    final PaymentCodec codec = new PaymentCodec(Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC), "10000.000", 300, 2592000, 65536);
    static final String JSON = """
      {"paymentId":"d92192df-f79a-4553-8120-5660d2eb1294","fromAccount":"ACC-1001","toAccount":"ACC-2002","amount":125.750,"date":"2026-10-28T13:30:00+03:00","purpose":" Invoice ","notes":"October"}
      """;
    @Test void canonicalizesBothFormatsIdentically() {
        var json = codec.parse(JSON.getBytes(StandardCharsets.UTF_8), "application/json");
        var xml = codec.parse("<payment><paymentId>d92192df-f79a-4553-8120-5660d2eb1294</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>2026-10-28T10:30:00Z</date><purpose>Invoice</purpose><notes>October</notes></payment>".getBytes(StandardCharsets.UTF_8), "application/xml");
        assertEquals(json.fingerprint(), xml.fingerprint());
        assertEquals("125.750", json.amount().toPlainString());
    }
    @Test void rejectsDuplicatesUnknownsAndCoercion() {
        for (var bad : new String[]{JSON.replace("\"amount\":125.750", "\"amount\":true"), JSON.replace("\"purpose\":", "\"unknown\":1,\"purpose\":"), JSON.replace("\"amount\":125.750", "\"amount\":125.750,\"amount\":1"), JSON.replace("\"notes\":\"October\"", "\"notes\":null")}) {
            assertEquals("MALFORMED_MESSAGE", assertThrows(ApiFailure.class, () -> codec.parse(bad.getBytes(StandardCharsets.UTF_8), "application/json")).code());
        }
    }
    @Test void collectsFieldIssues() {
        var bad = JSON.replace("125.750", "-1.0001").replace("ACC-2002", "ACC-1001").replace("2026-10-28T13:30:00+03:00", "2026-10-28").replace(" Invoice ", " ");
        var e = assertThrows(ApiFailure.class, () -> codec.parse(bad.getBytes(StandardCharsets.UTF_8), "application/json"));
        assertEquals("VALIDATION_FAILED", e.code()); assertTrue(e.issues().size() >= 4);
    }
    @Test void rejectsUnsafeXmlAndNestedOrRepeatedElements() {
        for (var bad : new String[]{"<!DOCTYPE payment [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><payment><purpose>&x;</purpose></payment>", "<payment><amount>1</amount><amount>2</amount></payment>", "<payment><purpose><nested>x</nested></purpose></payment>", "<payment xmlns:x='http://www.w3.org/2001/XInclude'><x:include href='file:///etc/passwd'/></payment>"}) {
            assertEquals("MALFORMED_MESSAGE", assertThrows(ApiFailure.class, () -> codec.parse(bad.getBytes(StandardCharsets.UTF_8), "application/xml")).code());
        }
    }
    @Test void enforcesTimePrecisionAndLength() {
        for (String bad : new String[]{JSON.replace("125.750", "1.0000"), JSON.replace("125.750", "0"), JSON.replace("125.750", "10000.001"), JSON.replace("13:30:00+03:00", "13:36:00+03:00"), JSON.replace("2026-10-28", "2026-09-01"), JSON.replace("ACC-1001", " ACC-1001")}) {
            assertEquals("VALIDATION_FAILED", assertThrows(ApiFailure.class, () -> codec.parse(bad.getBytes(StandardCharsets.UTF_8), "application/json")).code());
        }
    }
}
