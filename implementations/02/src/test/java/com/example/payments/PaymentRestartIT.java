package com.example.payments;

import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual packaged process twice against one durable PostgreSQL database. */
class PaymentRestartIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Process process;
    private int port;
    private Path log;
    private String base(){return "http://localhost:"+port;}
    private HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token);
        if(method.equals("POST"))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else builder.GET();
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void start(PostgreSQLContainer<?> db,int attempt) throws Exception {
        try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        log=Path.of("target/restart-"+attempt+".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-XX:ActiveProcessorCount=2","-jar","target/quarkus-app/quarkus-run.jar");
        var env=builder.environment();env.putAll(Map.of("DB_URL",db.getJdbcUrl(),"DB_USER",db.getUsername(),"DB_PASSWORD",db.getPassword(),"JWT_ISSUER","https://test-issuer.example","JWT_PUBLIC_KEY_LOCATION",Path.of("src/test/resources/public-key.pem").toAbsolutePath().toString(),"QUARKUS_HTTP_PORT",String.valueOf(port),"QUARKUS_HTTP_INSECURE_REQUESTS","enabled","PAYMENTS_REQUIRE_TLS","false","QUARKUS_OTEL_SDK_DISABLED","true"));
        process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline&&process.isAlive()) {
            try {if(http.send(HttpRequest.newBuilder(URI.create(base()+"/q/health/ready")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}catch(Exception waiting){}
            Thread.sleep(100);
        }
        fail("Packaged application failed to start: "+Files.readString(log));
    }
    private void stop() throws Exception {if(process!=null){process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}}
    @Test void packagedServiceRestartPreservesReceiptLedgerAndExactlyOnceEffect() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:16-alpine")) {
            db.start();
            try {
                start(db,1);
                try(Connection c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());Statement s=c.createStatement()) {
                    s.execute("INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
                    s.execute("INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status) VALUES ('ACC-1001','alice','JOD',500,500,'ACTIVE'),('ACC-2002','bob','JOD',100,100,'ACTIVE')");
                }
                String pem=Files.readString(Path.of("src/test/resources/private-key.pem")).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
                PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
                String token=Jwt.issuer("https://test-issuer.example").subject("alice").audience("payments-api").expiresIn(600).claim("scope","payments:submit payments:read").sign(key);
                String id=UUID.randomUUID().toString();
                String body="{\"paymentId\":\""+id+"\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\""+OffsetDateTime.now(ZoneOffset.UTC)+"\",\"purpose\":\"Restart durability\"}";
                var first=request("POST","/api/v1/payments",token,body);assertEquals(201,first.statusCode(),first.body());
                stop();start(db,2);
                var retrieved=request("GET","/api/v1/payments/"+id,token,null);assertEquals(200,retrieved.statusCode());assertEquals(first.body(),retrieved.body());
                var replay=request("POST","/api/v1/payments",token,body);assertEquals(200,replay.statusCode());assertEquals(first.body(),replay.body());
                try(Connection c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());Statement s=c.createStatement()) {
                    try(ResultSet r=s.executeQuery("SELECT available_balance FROM accounts WHERE id='ACC-1001'")){assertTrue(r.next());assertEquals("374.250",r.getBigDecimal(1).toPlainString());}
                    try(ResultSet r=s.executeQuery("SELECT count(*),sum(signed_amount) FROM ledger_entries")){assertTrue(r.next());assertEquals(2,r.getInt(1));assertEquals("0.000",r.getBigDecimal(2).toPlainString());}
                }
                // Database failure marks readiness unhealthy and cannot move funds or persist a rejection.
                db.stop();
                var failure=request("POST","/api/v1/payments",token,body.replace(id,UUID.randomUUID().toString()));assertEquals(503,failure.statusCode());
                assertEquals(503,http.send(HttpRequest.newBuilder(URI.create(base()+"/q/health/ready")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }finally {stop();}
        }
    }
}
