package com.example.payments.api;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Keep both cleartext upgrades and TLS negotiation on the supported HTTP/1.1 adapter. */
@ApplicationScoped
public class HttpProtocolConfiguration implements HttpServerOptionsCustomizer {
  @Override
  public void customizeHttpServer(HttpServerOptions options) {
    options.setHttp2ClearTextEnabled(false);
  }

  @Override
  public void customizeHttpsServer(HttpServerOptions options) {
    options
        .setHttp2ClearTextEnabled(false)
        .setUseAlpn(false)
        .setAlpnVersions(List.of(HttpVersion.HTTP_1_1));
  }
}
