package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.PaymentService;
import com.example.payments.application.Reconciliation;
import com.example.payments.domain.Command;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class RestartDurabilityTest {
  @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(PaymentApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + db.getJdbcUrl(),
            "--spring.datasource.username=" + db.getUsername(),
            "--spring.datasource.password=" + db.getPassword(),
            "--payments.require-tls=false",
            "--management.tracing.sampling.probability=0",
            "--logging.level.root=WARN");
  }

  @Test
  void restartAndLostResponseStillDiscoverOriginalCommit() {
    var c =
        new Command(
            UUID.randomUUID(), "A", "B", new BigDecimal("125.750"), Instant.now(), "Invoice", "");
    Map<String, Object> receipt;
    try (var first = start()) {
      var jdbc = first.getBean(JdbcTemplate.class);
      jdbc.update(
          "INSERT INTO customers(id,display_name,status) VALUES"
              + " ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE')");
      jdbc.update(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES"
              + " ('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')");
      receipt = first.getBean(PaymentService.class).transfer(c, "alice").body();
    }
    // The first connection pool and servlet application are closed. Reopen against the same
    // persistent database.
    try (var second = start()) {
      var service = second.getBean(PaymentService.class);
      assertEquals(receipt, service.find(c.paymentId(), "alice"));
      var replay = service.transfer(c, "alice");
      assertEquals(200, replay.http());
      assertEquals(receipt, replay.body());
      assertEquals("374.250", service.balance("A", "alice").get("balance"));
      assertTrue(second.getBean(Reconciliation.class).report().isEmpty());
      assertEquals(
          2,
          second
              .getBean(JdbcTemplate.class)
              .queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    }
  }
}
