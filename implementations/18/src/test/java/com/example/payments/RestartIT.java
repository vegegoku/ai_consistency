package com.example.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged artifact in separate OS processes with one durable database. */
class RestartIT {
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final ObjectMapper mapper=new ObjectMapper();
    @Test void lostCommittedResponseAndProcessRestartRemainExactlyOnce()throws Exception {
        int databasePort;try(var reserved=new ServerSocket(0)){databasePort=reserved.getLocalPort();}
        try(var db=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("restart18").withUsername("payments").withPassword("test-only")
            .withCreateContainerCmdModifier(cmd->cmd.withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindPort(databasePort),new com.github.dockerjava.api.model.ExposedPort(5432))))){
            db.start();int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
            Process process=null;
            try {
                process=start(db,port,"first");waitReady(process,port);
                try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
                    s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
                    s.execute("INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('A','alice',500,500),('B','bob',100,100)");
                }
                String token=Jwt.issuer("https://test-issuer.example").subject("alice").audience("payment-service").issuedAt(Instant.now()).expiresIn(600).claim("scope","payments:write payments:read payments:admin").sign("privateKey.pem");
                UUID id=UUID.randomUUID();String body="{\"paymentId\":\""+id+"\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"125.750\",\"date\":\""+Instant.now()+"\",\"purpose\":\"Lost response\"}";
                // Send a complete request, deliberately never read the response, and wait for the DB commit.
                try(var socket=new Socket("localhost",port)){
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("POST /api/v1/payments HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
                    long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();boolean committed=false;
                    while(System.nanoTime()<deadline){
                        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.prepareStatement("select status from payments where payment_id=?")){
                            s.setObject(1,id);try(var r=s.executeQuery()){if(r.next()){assertEquals("COMPLETED",r.getString(1));committed=true;break;}}
                        }Thread.sleep(50);
                    }assertTrue(committed,"Unobserved request must commit before simulating process loss");
                }
                process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                process=start(db,port,"restarted");waitReady(process,port);
                var lookup=send(port,"GET","/api/v1/payments/"+id,token,null);assertEquals(200,lookup.statusCode());
                var retry=send(port,"POST","/api/v1/payments",token,body);assertEquals(200,retry.statusCode());assertEquals(lookup.body(),retry.body());
                var balance=send(port,"GET","/api/v1/accounts/A/balance",token,null);assertEquals("374.250",mapper.readTree(balance.body()).get("balance").asText());
                var report=send(port,"GET","/api/v1/admin/reconciliation",token,null);assertTrue(mapper.readTree(report.body()).get("clean").asBoolean());
                try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ledger_entries")){r.next();assertEquals(2,r.getLong(1));}
                db.getDockerClient().stopContainerCmd(db.getContainerId()).withTimeout(1).exec();
                var readiness=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/q/health/ready")).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,readiness.statusCode(),"Readiness must fail during database outage");
                assertEquals(503,send(port,"GET","/api/v1/payments/"+id,token,null).statusCode());
                db.getDockerClient().startContainerCmd(db.getContainerId()).exec();
                waitReady(process,port);
                assertEquals(200,send(port,"GET","/api/v1/payments/"+id,token,null).statusCode());
                assertEquals("374.250",mapper.readTree(send(port,"GET","/api/v1/accounts/A/balance",token,null).body()).get("balance").asText());
            }finally{if(process!=null){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}
        }
    }
    Process start(PostgreSQLContainer<?> db,int port,String phase)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        return new ProcessBuilder(java,"-Dquarkus.http.insecure-requests=enabled","-Dquarkus.http.port="+port,
            "-Dquarkus.datasource.jdbc.url="+db.getJdbcUrl(),"-Dquarkus.datasource.username="+db.getUsername(),"-Dquarkus.datasource.password="+db.getPassword(),
            "-Dmp.jwt.verify.issuer=https://test-issuer.example","-Dmp.jwt.verify.publickey.location="+Path.of("src/test/resources/publicKey.pem").toAbsolutePath(),
            "-Dquarkus.otel.sdk.disabled=true","-jar","target/quarkus-app/quarkus-run.jar")
            .redirectErrorStream(true).redirectOutput(Path.of("target/restart-"+phase+".log").toFile()).start();
    }
    void waitReady(Process process,int port)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Packaged service exited; inspect target/restart-*.log");
            try{if(client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/q/health/ready")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}catch(Exception ignored){}Thread.sleep(100);
        }fail("Packaged service did not become ready");
    }
    HttpResponse<String> send(int port,String method,String path,String token,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
