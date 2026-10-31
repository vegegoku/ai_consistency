package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.Map;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
    final PostgreSQLContainer<?> database=new PostgreSQLContainer<>("postgres:16-alpine");
    public Map<String,String> start() {
        database.start();return Map.of("quarkus.datasource.jdbc.url",database.getJdbcUrl(),"quarkus.datasource.username",database.getUsername(),"quarkus.datasource.password",database.getPassword());
    }
    public void stop(){database.stop();}
}
