# Verification results

Verified on 2026-10-28 with Temurin JDK 21.0.5, Maven 3.9.9, Docker 29.9.2 and PostgreSQL 16 through Testcontainers.

Command:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.5-tem" PATH="$HOME/.sdkman/candidates/java/21.0.5-tem/bin:$PATH" mvn verify -Dpayments.load=true
```

Result: **BUILD SUCCESS — 32 tests, 0 failures, 0 errors, 0 skipped**. The suite includes 20 PostgreSQL/API/operational tests, 9 strict message-parser tests, and 3 transport/rate-limit boundary tests. Independent JVMs are stopped and restarted to verify payment durability and safe replay. A restricted runtime database role performs a transfer and is denied financial-history changes.

Reference HTTP load result:

```text
requests=1000 target_rate=100/s duration=10s p95_ms=7.050985 runtime=21.0.5+11-LTS
```

The load check uses a real signed bearer JWT, HTTP listener, and PostgreSQL, schedules 1,000 requests at 100/second, checks every response is 201 and verifies reconciliation. Results describe this host only.

Additional checks:

- `docker compose config --quiet`: exit 0.
- `docker build -t payment-service-16:verification .`: exit 0, image built successfully.
- OpenAPI document parsed successfully as JSON-compatible YAML.

Deliverable: `target/payment-service-1.0.0.jar`. Full verification and container build logs are retained under `target/`. Production TLS/secrets, global gateway rate limits, backup retention infrastructure and restore drills require deployment configuration described in operations.md. Compose supplies an isolated-development identity configuration; the full Compose stack was syntax checked, while financial/API integration was verified against real PostgreSQL and signed JWTs in automated tests.
