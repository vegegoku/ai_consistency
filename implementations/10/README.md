# Payment service

Java 21, Maven and Quarkus backend for atomic JOD internal transfers. PostgreSQL is required in all environments; integration tests use a disposable Testcontainers PostgreSQL instance. Both JSON and hardened XML produce the same immutable command and SHA-256 fingerprint. Amounts use BigDecimal and NUMERIC(19,3), with no rounding.

## Build and run

```sh
mvn -B verify
docker compose up -d
mvn quarkus:dev
# In another shell after Flyway has migrated the database:
docker compose exec -T postgres psql -U payments -d payments < dev/seed.sql
```

`mvn verify` runs parser, API, security, PostgreSQL race, rollback, replay, reconciliation and immutable-record tests and packages `target/quarkus-app`. Docker must be available for integration tests. The tests generate a separate ephemeral database and use test-only RSA keys; no production credentials are bundled. Build a runtime container with `docker build -t payment-service .`.

Set `JWT_ISSUER`, `JWT_AUDIENCE`, and `JWT_PUBLIC_KEY_LOCATION` to your OIDC issuer, payments audience and public JWKS URL. The local sample principals are `alice` and `bob`; issue development JWTs from your development identity provider with the appropriate subject and scopes. `payments:write`, `payments:read` and `accounts:read` authorize the three public operations. `payments:admin` authorizes reconciliation. No authentication bypass exists. Dev/test profiles allow local HTTP; production requires HTTPS. See [operations](docs/operations.md) for direct TLS configuration and runtime database permissions.

## Requests

```sh
export TOKEN='<development bearer JWT with subject alice>'
export PAYMENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
export INSTRUCTION_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$INSTRUCTION_DATE\",\"purpose\":\"Invoice payment\"}"
curl http://localhost:8080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
# Reuse identical canonical content and the original ID for a cross-format replay:
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$INSTRUCTION_DATE</date><purpose>Invoice payment</purpose></payment>"
```

First completion returns 201 and Location; identical retries return 200 and the original receipt. Durable business rejections replay the original error body and status. GET always returns 200 for a visible stored completed or rejected outcome. Responses contain canonical three-place amount strings. Purpose/notes are Unicode whitespace trimmed; absent and null optional notes are equivalent; empty notes remain distinct. UUIDs must use canonical lowercase format. Account IDs are case sensitive and are never trimmed. Decimal exponent notation, whitespace in decimal strings, unknown fields, JSON duplicates and duplicate/nested XML elements are rejected. Null required fields produce field issues. XML DTD, external entities, namespaces, attributes and XInclude are forbidden. XML response root is `response`.

## Configuration

| Environment variable | Default |
| --- | --- |
| DB_URL | jdbc:postgresql://localhost:5432/payments |
| DB_USER / DB_PASSWORD | payments / local-only-password (development only) |
| PAYMENTS_MAX_AMOUNT | 10000.000 |
| PAYMENTS_MAX_BODY_BYTES | 65536; also set QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE consistently |
| PAYMENTS_MAX_AGE_SECONDS / PAYMENTS_FUTURE_SECONDS | 2592000 / 300 |
| PAYMENTS_RATE_PER_MINUTE | 6000 requests per authenticated principal across replicas |
| PAYMENTS_REQUIRE_TLS | true in production |
| PAYMENTS_RETENTION_DAYS | 2555 policy minimum; no automatic deletion |
| OTEL_EXPORTER_OTLP_ENDPOINT | http://localhost:4317 |
| PG_PORT | 5432, Compose local database port |

`PAYMENTS_FAILURE_INJECTION` defaults false and must remain false outside controlled tests. Test hooks execute only in process, without a public endpoint. Authentication failures, hidden account failures and responses never disclose balances, SQL or stack traces.

Flyway versioned SQL lives in `src/main/resources/db/migration`. Development auto-migrates at startup. In production run migrations separately with the owner role, then disable migrate-at-start and use least privilege runtime credentials. The dev seed is an explicit manual step and is not a migration.

OpenAPI is served at `/q/openapi`, with the checked-in contract in `src/main/resources/META-INF/openapi.json`. Liveness/readiness are `/q/health/live` and `/q/health/ready`; Prometheus metrics are `/q/metrics`. Operational reconciliation, backup/restore, retention and incident guidance are in [operations](docs/operations.md).

## Troubleshooting and performance

401 means the token signature, issuer, audience, expiry or bearer header is invalid. 403 means a scope or debit/recipient policy is missing. 404 hides inaccessible balances and payments. A 409 rejection is definitive; corrected instructions need a new ID. A 503 is transient or uncertain: query and retry the original ID. Configure connection pool and database resources before load testing. Run `k6 run -e BASE_URL=https://service -e TOKEN=... dev/load.js` against dedicated funded test accounts; the script targets 100 arrivals/second and p95 below 500ms. This engineering target must be measured on representative hardware; it is not a deployment guarantee.
