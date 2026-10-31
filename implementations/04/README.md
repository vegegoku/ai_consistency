# Payment processing backend (implementation 04)

Java 21, Maven 3.9+, Quarkus 3.15, PostgreSQL 16. The service supports exact JOD transfers, authenticated JSON/XML APIs, durable idempotency, immutable double-entry ledger, and reconciliation. The copied requirements document is the specification.

## Build and tests

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn verify
```

Docker must be available. Tests start disposable PostgreSQL containers, generate an ephemeral RSA signing key, and verify real database transactions and HTTP endpoints. Tests are never silently skipped when Docker is missing. Docker Java API 1.44 is selected in test resources for compatibility with current Docker daemons. The packaged application is `target/quarkus-app/quarkus-run.jar` with its adjacent dependency directories.

## Local setup

```sh
export DB_PASSWORD='choose-a-local-password'
docker compose up -d --wait
export DB_URL='jdbc:postgresql://localhost:5432/payments?connectTimeout=3&socketTimeout=10'
export JWT_ISSUER='https://your-issuer.example/realms/payments'
export JWT_AUDIENCE='payments-api'
export JWT_PUBLIC_KEY='https://your-issuer.example/realms/payments/protocol/openid-connect/certs'
mvn quarkus:dev
# In a second shell, after Flyway has initialized the schema:
docker compose exec -T postgres psql -U payments -d payments < ops/seed.sql
```

Local dev uses HTTP port 8080. No authentication bypass or fixed production credentials exist. Obtain an RS256 access token from your OAuth/OIDC issuer: subject `alice` matches the development source customer. Required claims are `sub`, `iss`, `aud`, `exp`, `iat`; issuer, audience, signature, expiry and scope are verified. Scopes: `payments:write`, `payments:read`, `payments:admin`. Ownership comes from database customer IDs or explicit `account_permissions` debit grants, never request data. Active destination accounts with `receive_enabled=true` accept inbound funds; a per-principal deny grant can restrict a destination further.

```sh
export PAYMENT_TOKEN='an-access-token-from-your-issuer'
PAYMENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
INSTRUCTION_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $PAYMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$INSTRUCTION_DATE\",\"purpose\":\"Invoice INV-2048\"}"
curl http://localhost:8080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $PAYMENT_TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $PAYMENT_TOKEN"
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $PAYMENT_TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$INSTRUCTION_DATE</date><purpose>Invoice INV-2048</purpose></payment>"
```

The last command replays the JSON instruction and returns 200 without moving funds again. Only purpose and notes are trimmed. Missing notes and empty notes share a fingerprint. Amounts normalize to exactly 3 decimals; instruction timestamps normalize to an instant. IDs are exact, case-sensitive canonical lowercase UUIDs; account IDs are not trimmed. Decimal exponents and leading signs are rejected. XML namespaces, attributes, DTD, entities, processing instructions, nested values and repeated elements are rejected. The XML receipt root is `response`; errors use `issues/item` elements.

## Configuration and deployment

Environment variables `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_KEY` configure database and identity. Standard Quarkus environment conversion applies to every property, for example `PAYMENTS_MAXIMUM=10000.000`, `PAYMENTS_MAX_BODY_BYTES=65536`, `PAYMENTS_FUTURE_SECONDS=300`, `PAYMENTS_MAX_AGE_SECONDS=2592000`, `PAYMENTS_RATE_PER_MINUTE=6000`. The rate threshold is per principal per UTC minute and enforced atomically in PostgreSQL across replicas. A body over the application limit returns 413. The server also applies a 1 MiB emergency limit and 10-second read timeout.

Production requires HTTPS: HTTP is disabled. Configure `QUARKUS_HTTP_SSL_CERTIFICATE_FILES` and `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES` with mounted TLS material, or configure a PKCS12 certificate store using Quarkus TLS settings. Do not override insecure request settings for public deployments. Container image: `docker build -t payment-service-04 .`; supply configuration and mount keys at runtime. Database credentials and TLS private keys belong in a secret manager. Public JWKS URLs must use HTTPS.

Flyway migrations run on startup. For production, run migrations using a separate migration identity and then set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` for runtime replicas. Grant runtime only SELECT/INSERT on payments and ledger, required UPDATE on the provisional payment, UPDATE on accounts, SELECT on customers/permissions, INSERT/UPDATE on rate buckets, and sequence USAGE. Do not grant DELETE, TRUNCATE, DDL or trigger disabling. `ops/runtime-grants.sql` provides the role policy. No ordinary API can create accounts, change balances, delete transactions, or grant permissions.

Payments and ledger are durable database records. A payment row's unique UUID is inserted first; concurrent duplicates wait on the unique index. Accounts lock in ascending ID order. Transfer, both ledger entries and final status commit together. Business rejection commits without balance changes; infrastructure errors roll back. Lock timeout is 2 seconds; statement timeout 5 seconds; transient serialization/deadlock/lock errors get at most 3 attempts with the original ID. JDBC connection/socket timeouts and pool acquisition are bounded. An ambiguous commit returns 503 and must be resolved with the same ID. Replays require a currently debit-authorized caller and original initiator (or admin).

## Operations and API reference

OpenAPI: `src/main/resources/META-INF/openapi.yaml`, served at `/q/openapi`. Health: `/q/health/live`, `/q/health/ready` (database readiness). Metrics: `/q/metrics`; restrict operational endpoints to a private network. Tracing uses OpenTelemetry; enable OTLP export with `OTEL_EXPORT_ENABLED=true` and configure the standard Quarkus OTLP endpoint. Structured JSON audit logs contain correlation ID, valid payment ID, HTTP outcome, error code and latency; payment rows retain initiator and originating correlation ID. Input correlation IDs must be canonical UUIDs or are replaced.

Read [operations](docs/operations.md) for reconciliation, retention, backups and incident response. JWT integration follows the [Quarkus 3.15 JWT verification guide](https://quarkus.io/version/3.15/guides/security-jwt/).

## Performance measurement

Run against an isolated, production-like deployment with sufficiently funded fixtures and a token valid for the whole benchmark:

```sh
PAYMENT_TOKEN='...' python3 scripts/load.py --url https://your-reference-host --seconds 60 --rate 100
```

For a fully disposable packaged HTTPS restart and 60-second load check, run `scripts/verify-packaged.sh` after selecting Java 21. It generates ephemeral keys, starts its own PostgreSQL container, validates receipt durability across two JVM processes, then cleans up private keys and containers.

The script sends fresh 0.001 JOD transfers at a constant arrival rate, reports errors and p95, and fails unless all succeed and p95 is under 500 ms. Capture hardware, pool sizes, TLS and database topology with results. This engineering target must be remeasured on release hardware; local CI race tests are correctness evidence rather than a production capacity guarantee.

## Troubleshooting

401: verify issuer/audience/key and token expiry; 403: verify write scope and source debit authority; hidden resources return 404. 409: inspect the stable rejection code and use a new ID only for corrected business instructions. 503: check readiness, database reachability, contention and timeouts; query or replay the original ID. Missing Docker or an unsupported Java version fails verification visibly. Flyway checksum mismatch requires an explicit new versioned migration; never rewrite applied migrations. Never paste tokens, full instructions, customer details or SQL errors into operational logs.
