package com.example.payments;

import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** A short repeatable local reference measurement. Deployment qualification uses ops/load-test.js. */
class ReferenceLoadIT {
    @Test void measures100ValidTransfersPerSecond()throws Exception {
        var harness=new RestartIT();Process process=null;
        try(var db=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("load18").withUsername("payments").withPassword("test-only")){
            db.start();int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
            try {
                process=harness.start(db,port,"load");harness.waitReady(process,port);
                try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());var s=c.createStatement()){
                    s.execute("INSERT INTO customers(id,display_name) VALUES('alice','Alice'),('bob','Bob')");
                    s.execute("INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('A','alice',500,500),('B','bob',100,100)");
                }
                String token=Jwt.issuer("https://test-issuer.example").subject("alice").audience("payment-service").issuedAt(Instant.now()).expiresIn(600).claim("scope","payments:write payments:read payments:admin").sign("privateKey.pem");
                for(int i=0;i<30;i++)assertEquals(201,harness.send(port,"POST","/api/v1/payments",token,body()).statusCode());
                final int requests=1000;List<Long> latencies=Collections.synchronizedList(new ArrayList<>());List<Integer> statuses=Collections.synchronizedList(new ArrayList<>());
                try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
                    List<Future<?>> pending=new ArrayList<>();long epoch=System.nanoTime();
                    for(int i=0;i<requests;i++){
                        long target=epoch+TimeUnit.MILLISECONDS.toNanos(i*10L);
                        long remaining=target-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
                        pending.add(executor.submit(()->{
                            long start=System.nanoTime();try{var response=harness.send(port,"POST","/api/v1/payments",token,body());statuses.add(response.statusCode());}
                            catch(Exception e){throw new RuntimeException(e);}finally{latencies.add(System.nanoTime()-start);}
                        }));
                    }
                    for(var request:pending)request.get(20,TimeUnit.SECONDS);
                }
                latencies.sort(Long::compareTo);double p95=latencies.get((int)Math.ceil(requests*.95)-1)/1_000_000.0;
                String report="requests="+requests+" offeredRate=100/s duration=10s errors="+statuses.stream().filter(s->s!=201).count()+" p95Ms="+p95+" processors="+Runtime.getRuntime().availableProcessors()+" java="+System.getProperty("java.version")+" postgres=16-alpine\n";
                Files.writeString(Path.of("target/reference-load.txt"),report);
                assertEquals(requests,statuses.size());assertTrue(statuses.stream().allMatch(s->s==201),report);assertTrue(p95<500,report);
                var reconciliation=harness.send(port,"GET","/api/v1/admin/reconciliation",token,null);assertTrue(harness.mapper.readTree(reconciliation.body()).get("clean").asBoolean());
            } finally{if(process!=null){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}
        }
    }
    String body(){return "{\"paymentId\":\""+UUID.randomUUID()+"\",\"fromAccount\":\"A\",\"toAccount\":\"B\",\"amount\":\"0.001\",\"date\":\""+Instant.now()+"\",\"purpose\":\"Reference load\"}";}
}
