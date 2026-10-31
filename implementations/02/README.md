# Payment processing service

Java 21 / Maven / Quarkus 3.40.1 service implementing `payment-processing-requirements.md`. JSON and XML share an immutable decimal command and PostgreSQL transactional transfer. The database serializes payment-ID claims, locks accounts in sorted order, commits both account balances and exactly two balanced ledger entries, and stores durable business rejections. Final payments, ledger entries and issue records are immutable.

## Build and test

Install JDK 21 and Maven 3.9+, and start Docker. Run:

```sh
mvn -B verify
```

JUnit and REST Assured tests start a fresh PostgreSQL 16 Testcontainer. They exercise exact JSON/XML accounting, strict types and duplicate/XXE protection, multiple validation issues, JWT issuer/audience/signature/expiry/scopes, ownership and explicit permissions, rejected replay, payload conflicts, concurrent duplicates/debits/opposing transfers, injected after-debit rollback, lost-response replay, bounded lock timeout, immutability and corrupted-fixture reconciliation. No H2 substitution is used. A Failsafe test launches the actual packaged process, commits a transfer, terminates and restarts the JVM, then checks the original receipt, balances and ledger before testing a real database outage.

## Local setup

```sh
docker compose up -d
mvn quarkus:dev
# In another terminal, after Flyway has started:
docker compose exec -T postgres psql -U payments -d payments < dev/seed.sql
TOKEN=$(python3 scripts/mint_dev_token.py alice)
PAYMENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October services\"}"
curl http://localhost:8080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

The development-only RSA key is in `src/test/resources`; it is public test material, never a production credential. Local tokens expire in ten minutes. Seeded customer subjects are `alice` and `bob`; the destination receive policy is explicitly PUBLIC. HTTP and local credentials are enabled only in the dev/test profiles. Test data is never seeded automatically in production.

Equivalent XML (use a new ID for a new transfer or reuse exactly equivalent fields for replay):

```sh
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose><notes>October services</notes></payment>"
```

First completion returns 201 and Location; equivalent retries return the original receipt with 200. Rejected retries preserve the original rejection even if balances later change. A changed payload with the same ID returns 409. Instruction-window and maximum policy checks apply to fresh IDs; historical equivalent retries still return their stored outcome. Purpose and notes are trimmed; absent and empty notes are equivalent. Accounts are compared exactly. UUIDs are interpreted as UUID identities. Decimal amounts are never rounded; receipts use exactly three decimals. XML rejects namespaces/attributes, nested values, duplicate/unknown elements, processing instructions, DTDs and entity references. JSON rejects duplicate/unknown properties, coercion and trailing documents.

## Configuration and deployment

| Environment/config | Meaning/default |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL JDBC URL, runtime role and secret; password required in production |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_KEY_LOCATION` | Trusted issuer, audience (`payments-api`), PEM/JWKS location; issuer/key required in production |
| `PAYMENTS_MAX_BODY_BYTES` | 65536; align Quarkus `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` and ingress limits |
| `PAYMENTS_MAX_AMOUNT` | 10000.000 JOD |
| `PAYMENTS_MAX_AGE_DAYS`, `PAYMENTS_FUTURE_SECONDS` | Instruction window: 30 days and 300 seconds |
| `PAYMENTS_LOCK_TIMEOUT_MS`, `PAYMENTS_STATEMENT_TIMEOUT_MS` | 1000 and 5000; lock wait per attempt bounded |
| `PAYMENTS_TRANSIENT_RETRIES` | 2; same ID is retained for retries |
| `PAYMENTS_RATE_PER_MINUTE` | 600 per authenticated principal, per replica |
| `PAYMENTS_RATE_MAX_PRINCIPALS` | 10000 cache bound |
| `PAYMENTS_RETENTION_YEARS` | 7 minimum documented retention; financial deletion is disabled |
| `OTEL_ENDPOINT` | OTLP endpoint, default http://localhost:4317 |

Production requires TLS certificate/key configuration and disables insecure HTTP. Configure PostgreSQL TLS and least-privilege roles. See [operations](docs/operations.md) for deployment, migrations, reconciliation, retention, backups and incidents. Flyway runs versioned migrations at startup for development; production should run migrations as a distinct owner before starting least-privilege runtime replicas. Build a runtime image with `docker build -t payments-02 .`, or run `java -jar target/quarkus-app/quarkus-run.jar`. Restart this process with the same database to preserve all financial state.

Health endpoints are `/q/health/live` and `/q/health/ready` (includes the database). Metrics are `/q/metrics`; OpenAPI is `/q/openapi` and its static source is `src/main/resources/META-INF/openapi.yaml`. Restrict management endpoints at ingress. Admin reconciliation requires `payments:admin`. Payment submission requires `payments:submit`, inquiries require `payments:read`, and account authorization is always checked in the database.

## Performance and troubleshooting

Run `JAVA_HOME=/path/to/jdk21 scripts/benchmark.sh` for an isolated PostgreSQL/application fixture with a one-minute, 100 requests/second Python load generator, followed by reconciliation. Alternatively run `TOKEN=... BASE_URL=https://... k6 run scripts/load.js` on representative hardware after provisioning at least 6.000 JOD, disabling per-account operational caps for the fixture and raising the principal's rate quota above 6000/minute. The script targets 100 requests/sec for one minute and checks p95 <500ms and >99% successful receipts. This is a release measurement, not a claimed hardware-independent guarantee. Reconcile after load.

A 401 means the bearer token is missing, expired, untrusted or incorrectly scoped/claimed; 403 means the scope or account/destination authorization failed. Account lookup returns 404 to unrelated principals, even for existing accounts. A 409 is a definitive business rejection; a 503 is retryable with the same ID and includes Retry-After. Do not change IDs after an unknown outcome. If Docker integration tests fail before service startup, check Docker socket access and image availability. If Maven uses Java 17, set JAVA_HOME to a JDK 21 installation and check `mvn -version`. For lock timeouts examine long transactions; for readiness failures check JDBC connectivity and secrets. Never disable ledger constraints to bypass a failing payment.

Measured local results and test evidence are in [verification](docs/verification.md).
