package com.example.payments;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresResource implements QuarkusTestResourceLifecycleManager {
  static KeyPair keys;
  PostgreSQLContainer<?> db;
  Path publicFile;

  public Map<String, String> start() {
    try {
      db = new PostgreSQLContainer<>("postgres:16-alpine");
      db.start();
      var gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      keys = gen.generateKeyPair();
      publicFile = Files.createTempFile("payments-public-key", ".pem");
      Files.writeString(
          publicFile,
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'})
                  .encodeToString(keys.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n");
      return Map.of(
          "quarkus.datasource.jdbc.url",
          db.getJdbcUrl(),
          "quarkus.datasource.username",
          db.getUsername(),
          "quarkus.datasource.password",
          db.getPassword(),
          "mp.jwt.verify.publickey.location",
          publicFile.toUri().toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void stop() {
    if (db != null) db.stop();
    try {
      if (publicFile != null) Files.deleteIfExists(publicFile);
    } catch (Exception ignored) {
    }
  }
}
