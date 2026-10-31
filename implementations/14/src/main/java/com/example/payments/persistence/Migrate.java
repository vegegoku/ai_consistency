package com.example.payments.persistence;

import com.example.payments.Config;

/** Run separately using migration-owner credentials before deploying least-privilege replicas. */
public final class Migrate {
  private Migrate() {}

  public static void main(String[] args) {
    try (var db = new Database(Config.environment(), true)) {
      if (!db.ready()) throw new IllegalStateException("Database migration readiness failed");
    }
  }
}
