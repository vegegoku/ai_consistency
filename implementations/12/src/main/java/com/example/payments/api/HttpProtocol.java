package com.example.payments.api;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import jakarta.inject.Singleton;
import java.util.List;

/** HTTP/2 terminates at ingress; bounded body buffering uses the HTTP/1.1 application listener. */
@Singleton
public class HttpProtocol implements HttpServerOptionsCustomizer {
  @Override
  public void customizeHttpServer(HttpServerOptions options) {
    options.setHttp2ClearTextEnabled(false).setAlpnVersions(List.of(HttpVersion.HTTP_1_1));
  }

  @Override
  public void customizeHttpsServer(HttpServerOptions options) {
    customizeHttpServer(options);
  }
}
