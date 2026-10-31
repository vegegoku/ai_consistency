package com.example.payments.api;

import com.example.payments.*;
import com.example.payments.application.PaymentService;
import com.example.payments.domain.*;
import com.example.payments.observability.Telemetry;
import com.example.payments.persistence.Database;
import com.example.payments.security.*;
import com.example.payments.security.Authenticator;
import com.sun.net.httpserver.*;
import io.opentelemetry.api.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class ApiServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore slots = new Semaphore(256);
  private final Config config;
  private final Codec codec;
  private final PaymentService service;
  private final Database db;
  private final Authenticator auth;
  private final RateLimiter limiter;
  private final Telemetry telemetry;

  public ApiServer(
      Config config,
      Codec codec,
      PaymentService service,
      Database db,
      Authenticator auth,
      Telemetry telemetry)
      throws Exception {
    this.config = config;
    this.codec = codec;
    this.service = service;
    this.db = db;
    this.auth = auth;
    this.telemetry = telemetry;
    limiter = new RateLimiter(config.rateLimit(), Clock.systemUTC());
    System.setProperty("sun.net.httpserver.maxReqTime", "10");
    System.setProperty("sun.net.httpserver.maxRspTime", "10");
    System.setProperty("sun.net.httpserver.maxReqHeaders", "50");
    if (config.development()) {
      server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 128);
    } else {
      if (config.tlsStore().isBlank())
        throw new IllegalStateException("TLS_KEYSTORE is required outside DEV_MODE");
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(Path.of(config.tlsStore()))) {
        store.load(in, config.tlsPassword().toCharArray());
      }
      var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(store, config.tlsPassword().toCharArray());
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(km.getKeyManagers(), null, null);
      var https = HttpsServer.create(new InetSocketAddress("0.0.0.0", config.port()), 128);
      https.setHttpsConfigurator(
          new HttpsConfigurator(ssl) {
            @Override
            public void configure(HttpsParameters p) {
              var parameters = getSSLContext().getDefaultSSLParameters();
              parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
              p.setSSLParameters(parameters);
            }
          });
      server = https;
    }
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    long started = System.nanoTime();
    String correlation = UUID.randomUUID().toString(),
        payment = null,
        code = "OK",
        outcomeStatus = null;
    int http = 500;
    boolean xml = false, acquired = slots.tryAcquire();
    var parent =
        GlobalOpenTelemetry.getPropagators()
            .getTextMapPropagator()
            .extract(
                Context.current(),
                exchange.getRequestHeaders(),
                new TextMapGetter<Headers>() {
                  public Iterable<String> keys(Headers h) {
                    return h.keySet();
                  }

                  public String get(Headers h, String key) {
                    return h.getFirst(key);
                  }
                });
    Span span =
        GlobalOpenTelemetry.getTracer("payment-service")
            .spanBuilder("http.request")
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
      exchange.getResponseHeaders().set("X-Correlation-ID", correlation);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      if (!config.development())
        exchange.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
      GlobalOpenTelemetry.getPropagators()
          .getTextMapPropagator()
          .inject(
              Context.current(),
              exchange.getResponseHeaders(),
              (headers, k, v) -> headers.set(k, v));
      if (!acquired) throw new ApiException(503, "TEMPORARILY_UNAVAILABLE");
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if (method.equals("GET") && (path.equals("/health/live") || path.equals("/health/ready"))) {
        boolean ready = path.endsWith("live") || db.ready();
        http = ready ? 200 : 503;
        code = ready ? "UP" : "TEMPORARILY_UNAVAILABLE";
        send(exchange, http, Map.of("status", ready ? "UP" : "DOWN"), false);
        return;
      }
      if (method.equals("GET") && path.equals("/openapi.yaml")) {
        try (var in = getClass().getResourceAsStream("/openapi.yaml")) {
          if (in == null) throw new ApiException(404, "NOT_FOUND");
          byte[] bytes = in.readAllBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/yaml");
          http = 200;
          exchange.sendResponseHeaders(http, bytes.length);
          exchange.getResponseBody().write(bytes);
          return;
        }
      }
      if (!path.equals("/metrics"))
        xml = negotiate(exchange.getRequestHeaders().getFirst("Accept"));
      Caller caller = auth.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
      limiter.check(caller.subject());
      if (path.equals("/metrics") && method.equals("GET")) {
        admin(caller);
        byte[] body = telemetry.metrics.scrape().getBytes(StandardCharsets.UTF_8);
        http = 200;
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        exchange.sendResponseHeaders(http, body.length);
        exchange.getResponseBody().write(body);
        return;
      }
      if (path.equals("/api/v1/admin/reconciliation") && method.equals("GET")) {
        admin(caller);
        http = 200;
        send(exchange, http, Map.of("issues", service.reconcile()), xml);
        return;
      }
      if (path.equals("/api/v1/payments") && method.equals("POST")) {
        if (!caller.permits("payments:submit")) throw new ApiException(403, "FORBIDDEN");
        String media = exchange.getRequestHeaders().getFirst("Content-Type");
        media = media == null ? "" : media.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!Set.of("application/json", "application/xml").contains(media)
            || exchange.getRequestHeaders().getFirst("Content-Encoding") != null)
          throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE");
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
          try {
            if (Long.parseLong(length) > config.bodyLimit())
              throw new ApiException(413, "PAYLOAD_TOO_LARGE");
          } catch (NumberFormatException e) {
            throw new ApiException(400, "MALFORMED_MESSAGE");
          }
        }
        var command =
            codec.parse(exchange.getRequestBody().readNBytes(config.bodyLimit() + 1), media);
        payment = command.paymentId();
        var result = service.transfer(command, caller, correlation);
        http = result.http();
        outcomeStatus = (String) result.body().get("status");
        code = (String) result.body().getOrDefault("code", "COMPLETED");
        if (http == 201)
          exchange.getResponseHeaders().set("Location", "/api/v1/payments/" + command.paymentId());
        send(exchange, http, result.body(), xml);
        return;
      }
      String[] segments = path.split("/");
      if (method.equals("GET") && segments.length == 5 && path.startsWith("/api/v1/payments/")) {
        var body = service.payment(segments[4], caller);
        payment = (String) body.get("paymentId");
        outcomeStatus = (String) body.get("status");
        http = 200;
        send(exchange, http, body, xml);
        return;
      }
      if (method.equals("GET")
          && segments.length == 6
          && path.startsWith("/api/v1/accounts/")
          && segments[5].equals("balance")) {
        http = 200;
        send(exchange, http, service.balance(segments[4], caller), xml);
        return;
      }
      throw new ApiException(404, "NOT_FOUND");
    } catch (ApiException e) {
      http = e.http;
      code = e.code;
      if (e.paymentId != null) payment = e.paymentId;
      span.setStatus(StatusCode.ERROR, code);
      if (http == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
      if (http == 429 || http == 503)
        exchange.getResponseHeaders().set("Retry-After", http == 429 ? "60" : "2");
      send(exchange, http, error(e, correlation), xml);
    } catch (Exception e) {
      http = 500;
      code = "INTERNAL_ERROR";
      telemetry.count("payments.failures", code);
      span.setStatus(StatusCode.ERROR, code);
      send(exchange, http, error(new ApiException(500, code), correlation), xml);
    } finally {
      exchange.close();
      if (acquired) slots.release();
      span.setAttribute("http.response.status_code", http);
      span.end();
      try {
        telemetry
            .metrics
            .timer("payments.request.latency", "code", code)
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        telemetry.count("payments.requests", code);
        telemetry.event(
            correlation,
            payment,
            outcomeStatus != null ? outcomeStatus : http < 400 ? "OK" : "ERROR",
            code,
            System.nanoTime() - started);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void admin(Caller caller) {
    if (!caller.permits("payments:admin")) throw new ApiException(403, "FORBIDDEN");
  }

  public static boolean negotiate(String header) {
    if (header == null || header.isBlank()) return false;
    double json = -1, xml = -1;
    for (String part : header.split(",")) {
      String[] params = part.strip().split(";");
      String type = params[0].strip().toLowerCase(Locale.ROOT);
      double q = 1;
      for (int i = 1; i < params.length; i++) {
        String parameter = params[i].strip();
        if (parameter.startsWith("q=")) {
          try {
            q = Double.parseDouble(parameter.substring(2));
            if (!Double.isFinite(q) || q < 0 || q > 1) q = 0;
          } catch (Exception e) {
            q = 0;
          }
        }
      }
      if (type.equals("application/json")) json = q;
      else if (type.equals("application/xml")) xml = q;
      else if (type.equals("*/*") || type.equals("application/*")) {
        if (json < 0) json = q;
        if (xml < 0) xml = q;
      }
    }
    if (json <= 0 && xml <= 0) throw new ApiException(406, "NOT_ACCEPTABLE");
    return xml > json;
  }

  private Map<String, Object> error(ApiException e, String correlation) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (e.paymentId != null) body.put("paymentId", e.paymentId);
    body.put("status", "ERROR");
    body.put("code", e.code);
    body.put("message", e.getMessage());
    body.put("issues", e.issues);
    body.put("correlationId", correlation);
    body.put("timestamp", Instant.now().toString());
    return body;
  }

  private void send(HttpExchange exchange, int status, Object body, boolean xml)
      throws IOException {
    byte[] bytes = codec.write(body, xml);
    exchange
        .getResponseHeaders()
        .set(
            "Content-Type",
            xml ? "application/xml; charset=utf-8" : "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(1);
    executor.close();
  }
}
