package com.example.payments;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.Map;
public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer<?> database;
    @Override public Map<String,String> start(){
        database=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("payments18").withUsername("payments").withPassword("payments-test-only");
        database.start();return Map.of("quarkus.datasource.jdbc.url",database.getJdbcUrl(),"quarkus.datasource.username",database.getUsername(),"quarkus.datasource.password",database.getPassword(),"payments.rate-per-minute","10000");
    }
    @Override public void stop(){if(database!=null)database.stop();}
}
