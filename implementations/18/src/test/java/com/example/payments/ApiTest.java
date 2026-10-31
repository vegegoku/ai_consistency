package com.example.payments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest @QuarkusTestResource(PostgresResource.class)
class ApiTest {
    @Inject DataSource db;
    @BeforeEach void seed()throws Exception{
        try(var c=db.getConnection();var s=c.createStatement()){
            s.execute("TRUNCATE payment_issues,ledger_entries,payments,account_grants,accounts,customers,rate_buckets CASCADE");
            s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
            s.execute("INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('A','alice',500,500),('B','bob',100,100)");
        }
    }
    String token(String subject,String scopes){return Jwt.issuer("https://test-issuer.example").subject(subject).audience("payment-service").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("scope",scopes).sign("privateKey.pem");}
    String json(UUID id){return """
        {"paymentId":"%s","fromAccount":"A","toAccount":"B","amount":125.750,"date":"%s","purpose":"Invoice"}
        """.formatted(id,Instant.now());}
    @Test void jsonSubmissionReplayLookupAndBalance(){
        UUID id=UUID.randomUUID();String body=json(id),auth=token("alice","payments:write payments:read");
        given().auth().oauth2(auth).contentType("application/json").body(body).post("/api/v1/payments").then().statusCode(201).header("Location",endsWith(id.toString())).header("X-Correlation-ID",notNullValue()).body("amount",equalTo("125.750")).body("status",equalTo("COMPLETED"));
        given().auth().oauth2(auth).contentType("application/json").body(body).post("/api/v1/payments").then().statusCode(200);
        given().auth().oauth2(auth).get("/api/v1/payments/"+id).then().statusCode(200).body("amount",equalTo("125.750"));
        given().auth().oauth2(auth).get("/api/v1/accounts/A/balance").then().statusCode(200).body("balance",equalTo("374.250"));
    }
    @Test void xmlInputAndResponse(){
        String xml="<payment><paymentId>"+UUID.randomUUID()+"</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.750</amount><date>"+Instant.now()+"</date><purpose>Invoice</purpose></payment>";
        given().auth().oauth2(token("alice","payments:write")).contentType("application/xml").accept("application/xml").body(xml).post("/api/v1/payments").then().statusCode(201).body("receipt.amount",equalTo("125.750"));
    }
    @Test void authScopesOwnershipAndExistenceProtection(){
        given().contentType("application/json").body(json(UUID.randomUUID())).post("/api/v1/payments").then().statusCode(401).body("code",equalTo("UNAUTHENTICATED"));
        given().auth().oauth2("invalid").get("/api/v1/accounts/A/balance").then().statusCode(401);
        given().auth().oauth2(token("alice","payments:read")).contentType("application/json").body(json(UUID.randomUUID())).post("/api/v1/payments").then().statusCode(403);
        given().auth().oauth2(token("bob","payments:write")).contentType("application/json").body(json(UUID.randomUUID())).post("/api/v1/payments").then().statusCode(403).body("code",equalTo("FORBIDDEN"));
        given().auth().oauth2(token("bob","payments:read")).get("/api/v1/accounts/A/balance").then().statusCode(404);
    }
    @Test void issuerAudienceAndExpiryAreEnforced(){
        var expired=Jwt.issuer("https://test-issuer.example").subject("alice").audience("payment-service").issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).claim("scope","payments:read").sign("privateKey.pem");
        var audience=Jwt.issuer("https://test-issuer.example").subject("alice").audience("other-service").expiresIn(300).claim("scope","payments:read").sign("privateKey.pem");
        var issuer=Jwt.issuer("https://other-issuer.example").subject("alice").audience("payment-service").expiresIn(300).claim("scope","payments:read").sign("privateKey.pem");
        for(String t:List.of(expired,audience,issuer))given().auth().oauth2(t).get("/api/v1/accounts/A/balance").then().statusCode(401).body("code",equalTo("UNAUTHENTICATED"));
    }
    @Test void tamperedSignatureFailsAuthentication(){
        String valid=token("alice","payments:read");int dot=valid.lastIndexOf('.')+1;
        String tampered=valid.substring(0,dot)+(valid.charAt(dot)=='A'?"B":"A")+valid.substring(dot+1);
        given().auth().oauth2(tampered).get("/api/v1/accounts/A/balance").then().statusCode(401);
    }
    @Test void mediaAndMalformedValidation(){
        String auth=token("alice","payments:write");
        given().auth().oauth2(auth).contentType("text/plain").body("x").post("/api/v1/payments").then().statusCode(415).body("code",equalTo("UNSUPPORTED_MEDIA_TYPE"));
        given().auth().oauth2(auth).contentType("application/json").accept("text/html").body(json(UUID.randomUUID())).post("/api/v1/payments").then().statusCode(406);
        given().auth().oauth2(auth).contentType("application/json").body("{bad").post("/api/v1/payments").then().statusCode(400).body("code",equalTo("MALFORMED_MESSAGE"));
        given().auth().oauth2(auth).contentType("application/json").body("{}").post("/api/v1/payments").then().statusCode(400).body("issues.size()",greaterThan(3));
    }
    @Test void healthAndAdminReconciliation(){
        given().get("/q/health/live").then().statusCode(200);given().get("/q/health/ready").then().statusCode(200);
        given().auth().oauth2(token("alice","payments:read")).get("/api/v1/admin/reconciliation").then().statusCode(403);
        given().auth().oauth2(token("operator","payments:admin")).get("/api/v1/admin/reconciliation").then().statusCode(200).body("clean",equalTo(true));
    }
    @Test void durableRejectionRateLimitAndUnsafeXml()throws Exception {
        String auth=token("alice","payments:write payments:read");UUID id=UUID.randomUUID();String body=json(id).replace("125.750","501.000");
        var original=given().auth().oauth2(auth).contentType("application/json").body(body).post("/api/v1/payments").then().statusCode(409).body("status",equalTo("REJECTED")).body("code",equalTo("INSUFFICIENT_FUNDS")).extract().asString();
        given().auth().oauth2(auth).contentType("application/json").body(body).post("/api/v1/payments").then().statusCode(409).body(equalTo(original));
        given().auth().oauth2(auth).get("/api/v1/payments/"+id).then().statusCode(200).body("status",equalTo("REJECTED"));
        given().auth().oauth2(auth).contentType("application/xml").body("<!DOCTYPE payment [<!ENTITY x SYSTEM 'http://127.0.0.1:1/'>]><payment><purpose>&x;</purpose></payment>").post("/api/v1/payments").then().statusCode(400).body("code",equalTo("MALFORMED_MESSAGE"));
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("UPDATE rate_buckets SET requests=10000 WHERE principal='alice'");}
        given().auth().oauth2(auth).get("/api/v1/accounts/A/balance").then().statusCode(429).header("Retry-After",equalTo("60")).body("code",equalTo("RATE_LIMITED"));
    }
    @Test void crossFormatReplayReturnsOriginalReceipt(){
        String auth=token("alice","payments:write");UUID id=UUID.randomUUID();String date=Instant.now().toString();
        String json="{\"paymentId\":\""+id+"\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"125.750\",\"date\":\""+date+"\",\"purpose\":\"Invoice\"}";
        String original=given().auth().oauth2(auth).contentType("application/json").body(json).post("/api/v1/payments").then().statusCode(201).extract().asString();
        String xml="<payment><paymentId>"+id+"</paymentId><fromAccount>A</fromAccount><toAccount>B</toAccount><amount>125.75</amount><date>"+date+"</date><purpose> Invoice </purpose></payment>";
        given().auth().oauth2(auth).contentType("application/xml").accept("application/json").body(xml).post("/api/v1/payments").then().statusCode(200).body(equalTo(original));
    }
}
