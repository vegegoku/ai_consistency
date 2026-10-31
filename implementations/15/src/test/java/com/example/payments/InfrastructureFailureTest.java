package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.persistence.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class InfrastructureFailureTest {
  @Test
  void poolExhaustionBeforeTransactionIsRetryableInfrastructureError() {
    var unavailable =
        new AbstractDataSource() {
          public Connection getConnection() throws SQLException {
            throw new SQLTransientConnectionException("pool exhausted");
          }

          public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
          }
        };
    var jdbc = new JdbcTemplate(unavailable);
    var service =
        new PaymentService(
            jdbc,
            new AccountRepository(jdbc),
            new DataSourceTransactionManager(unavailable),
            new ObjectMapper(),
            Clock.systemUTC(),
            new SimpleMeterRegistry(),
            2);
    var command =
        new PaymentCommand(
            UUID.randomUUID(),
            "A",
            "B",
            new BigDecimal("1.000"),
            Instant.now(),
            "pool failure",
            "");
    var error =
        assertThrows(PaymentException.class, () -> service.transfer(command, "alice", "failure"));
    assertEquals(503, error.httpStatus());
    assertEquals("TEMPORARILY_UNAVAILABLE", error.code());
  }
}
