package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.application.PaymentService;
import com.example.payments.domain.PaymentCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
            "--spring.flyway.user=" + db.getUsername(),
            "--spring.flyway.password=" + db.getPassword(),
            "--payments.require-https=false",
            "--management.tracing.enabled=false",
            "--logging.level.root=WARN");
  }

  @Test
  void closingAndRestartingApplicationPreservesReceiptBalancesAndReplayGuard() {
    var command =
        new PaymentCommand(
            UUID.randomUUID(), "A", "B", new BigDecimal("125.750"), Instant.now(), "restart", "");
    Map<String, Object> original;
    try (var app = start()) {
      var jdbc = app.getBean(JdbcTemplate.class);
      jdbc.update(
          "INSERT INTO customers(id,display_name,subject)"
              + " VALUES('one','Alice','alice'),('two','Bob','bob')");
      jdbc.update(
          "INSERT INTO accounts(id,customer_id,available_balance,opening_balance)"
              + " VALUES('A','one',500,500),('B','two',100,100)");
      original =
          app.getBean(PaymentService.class).transfer(command, "alice", "before-restart").body();
    }
    try (var app = start()) {
      var service = app.getBean(PaymentService.class);
      assertEquals(original, service.payment(command.paymentId(), "alice"));
      assertEquals("374.250", service.balance("A", "alice").get("availableBalance"));
      assertEquals(200, service.transfer(command, "alice", "after-restart").httpStatus());
      assertEquals(
          2,
          app.getBean(JdbcTemplate.class)
              .queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
      assertTrue(service.reconcile().healthy());
    }
  }
}
