package com.example.payments;

import com.example.payments.api.*;
import com.example.payments.application.*;
import com.example.payments.observability.*;
import com.example.payments.persistence.*;
import com.example.payments.security.*;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.time.*;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    Config config = Config.environment();
    var otel =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(
                () ->
                    java.util.Map.of(
                        "otel.service.name",
                        "payment-service",
                        "otel.traces.exporter",
                        "none",
                        "otel.metrics.exporter",
                        "none",
                        "otel.logs.exporter",
                        "none"))
            .setResultAsGlobal()
            .build();
    var db =
        new Database(
            config,
            Boolean.parseBoolean(
                System.getenv()
                    .getOrDefault("MIGRATE_ON_START", Boolean.toString(config.development()))));
    var telemetry = new Telemetry();
    var codec =
        new Codec(
            config.bodyLimit(),
            config.maxAmount(),
            config.maxAge(),
            config.futureSkew(),
            Clock.systemUTC());
    var service = new PaymentService(db, codec, telemetry);
    var auth =
        new Authenticator(config.jwksFile(), config.issuer(), config.audience(), Clock.systemUTC());
    var server = new ApiServer(config, codec, service, db, auth, telemetry);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.close();
                  db.close();
                  telemetry.close();
                  otel.getOpenTelemetrySdk().close();
                }));
    server.start();
    telemetry.event(java.util.UUID.randomUUID().toString(), null, "STARTED", "READY", 0);
  }
}
