package com.example.payments;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PaymentApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaymentApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
