package com.example.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ProcessDurabilityIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final ObjectMapper json=new ObjectMapper();
    int freePort() throws Exception {try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
    Process boot(int port,String jwks,String suffix)throws Exception{
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","target/payment-service-1.0.0.jar","--server.port="+port,"--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),"--payments.require-https=false","--payments.jwt.issuer=https://test-issuer","--payments.jwt.jwk-set-uri="+jwks,"--management.tracing.enabled=false")
                .redirectErrorStream(true).redirectOutput(Path.of("target/process-"+suffix+".log").toFile()).start();
    }
    void ready(int port,Process process)throws Exception{
        for(int i=0;i<120;i++){
            if(!process.isAlive())fail("Application exited; inspect target/process-*.log");
            try{var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200)return;}catch(Exception ignored){}
            Thread.sleep(250);
        }
        fail("Readiness deadline exceeded");
    }
    HttpResponse<String> request(int port,String path,String token,String body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else builder.GET();
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    void stop(Process process)throws Exception{if(process!=null){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}}
    @Test void actualRestartAndResponseLossRecoverOneTransferAndMeasure100RequestsPerSecond()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var jwk=new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey)pair.getPublic()).keyID("test-key").build();byte[] keys=new JWKSet(jwk).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var identity=HttpServer.create(new InetSocketAddress("localhost",0),0);identity.createContext("/jwks",exchange->{exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,keys.length);try(var out=exchange.getResponseBody()){out.write(keys);}});identity.start();
        var signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),new JWTClaimsSet.Builder().issuer("https://test-issuer").audience("payment-service").subject("alice").expirationTime(java.util.Date.from(Instant.now().plusSeconds(300))).claim("scope","payments:read payments:write").build());signed.sign(new RSASSASigner((RSAPrivateKey)pair.getPrivate()));String token=signed.serialize();
        int port=freePort();Process process=null;
        try{
            String jwks="http://localhost:"+identity.getAddress().getPort()+"/jwks";process=boot(port,jwks,"first");ready(port,process);
            try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var statement=connection.createStatement()){
                statement.execute("INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");statement.execute("INSERT INTO accounts(id,customer_id,opening_balance,available_balance) VALUES ('ACC-1001','alice',500,500),('ACC-2002','bob',100,100)");
            }
            UUID id=UUID.randomUUID();String instruction=json.writeValueAsString(Map.of("paymentId",id.toString(),"fromAccount","ACC-1001","toAccount","ACC-2002","amount","125.750","date",Instant.now().toString(),"purpose","durability"));
            // Discard the receipt to model a client losing the response after server commit.
            assertEquals(201,request(port,"/api/v1/payments",token,instruction).statusCode());stop(process);process=null;
            process=boot(port,jwks,"restarted");ready(port,process);
            var receipt=request(port,"/api/v1/payments/"+id,token,null);assertEquals(200,receipt.statusCode());assertEquals("COMPLETED",json.readTree(receipt.body()).get("status").asText());assertEquals(200,request(port,"/api/v1/payments",token,instruction).statusCode());
            assertEquals("374.250",json.readTree(request(port,"/api/v1/accounts/ACC-1001/balance",token,null).body()).get("balance").asText());
            // A real HTTP/JWT/PostgreSQL short engineering smoke load, not a capacity guarantee.
            var latencies=new ArrayList<Long>();var jobs=new ArrayList<Future<Long>>();long start=System.nanoTime();
            try(var pool=Executors.newFixedThreadPool(20)){
                for(int i=0;i<200;i++){
                    long scheduled=start+i*10_000_000L;long wait=scheduled-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);
                    jobs.add(pool.submit(()->{String body=json.writeValueAsString(Map.of("paymentId",UUID.randomUUID().toString(),"fromAccount","ACC-1001","toAccount","ACC-2002","amount","0.001","date",Instant.now().toString(),"purpose","load"));long began=System.nanoTime();var result=request(port,"/api/v1/payments",token,body);assertEquals(201,result.statusCode(),result.body());return (System.nanoTime()-began)/1_000_000;}));
                }
                for(var job:jobs)latencies.add(job.get(20,TimeUnit.SECONDS));
            }
            Collections.sort(latencies);long p95=latencies.get((int)Math.ceil(latencies.size()*0.95)-1);
            Files.writeString(Path.of("target/load-report.json"),json.writeValueAsString(Map.of("requests",200,"offeredRequestsPerSecond",100,"durationSeconds",(System.nanoTime()-start)/1e9,"p95Milliseconds",p95,"targetMilliseconds",500,"targetMet",p95<500,"scope","local HTTP/JWT/PostgreSQL engineering smoke")));
            try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var statement=connection.createStatement();var rows=statement.executeQuery("SELECT (SELECT count(*) FROM payments),(SELECT count(*) FROM ledger_entries),(SELECT sum(available_balance) FROM accounts)")){
                assertTrue(rows.next());assertEquals(201,rows.getInt(1));assertEquals(402,rows.getInt(2));assertEquals("600.000",rows.getBigDecimal(3).toPlainString());
            }
        }finally{stop(process);identity.stop(0);}
    }
}
