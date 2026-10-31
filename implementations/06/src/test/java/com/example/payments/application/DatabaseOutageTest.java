package com.example.payments.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.domain.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseOutageTest {
  @Test
  void poolAcquisitionTimeoutIsRetryableEvenWithoutSqlState() {
    var service = new PaymentService();
    service.metrics = new SimpleMeterRegistry();
    service.dataSource =
        (DataSource)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  throw new SQLTransientConnectionException("Pool acquisition timed out");
                });
    var error =
        assertThrows(
            PaymentException.class,
            () ->
                service.transfer(
                    new PaymentCommand(
                        java.util.UUID.randomUUID(),
                        "A",
                        "B",
                        new java.math.BigDecimal("1.000"),
                        java.time.Instant.now(),
                        "Outage probe",
                        ""),
                    "alice",
                    java.util.UUID.randomUUID().toString()));
    assertEquals(503, error.http);
    assertEquals("TEMPORARILY_UNAVAILABLE", error.code);
  }
}
