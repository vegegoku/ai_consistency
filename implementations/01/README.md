# Payment service

Java 21 / Maven / Quarkus 3.15 backend using PostgreSQL 16. Exact decimal JOD transfers, JSON/XML, durable idempotency, transactional signed ledger, JWT authorization, rate limits and reconciliation.

## Build and test

Install JDK 21, Maven 3.9+, and Docker. Run `mvn verify`. All tests use disposable real PostgreSQL containers; no in-memory substitute and no silently skipped database tests. Test resources pin Docker API 1.44 for current Docker compatibility. The build produces `target/quarkus-app/quarkus-run.jar`. API tests use a random HTTP port and an ephemeral RSA/JWKS issuer to verify real JWT signature, audience, issuer, expiration and scopes; test-only identity overrides also exercise account access controls.

## Local development

```
docker compose up -d
export OIDC_URL=http://localhost:8180/realms/payments
mvn quarkus:dev
```

Local-only Flyway seeds create Alice's `ACC-1001` with `500.000` JOD and Bob's `ACC-2002` with `100.000`. Development Keycloak realm import contains deliberately named local passwords and is never loaded by production. Wait until Keycloak is ready, then get a development token:

```
TOKEN=$(curl -fsS http://localhost:8180/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payment-service \
  -d username=alice -d password=alice-development-only | jq -r .access_token)
PAYMENT_ID=$(uuidgen | tr 'A-F' 'a-f')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\"}"
curl http://localhost:8080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

Equivalent XML retry (same timestamp and fields) returns HTTP 200 and the original receipt:

```
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  --data "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose></payment>"
```

OpenAPI with JSON/XML examples and error responses is served at `/q/openapi`; the checked-in contract is `src/main/resources/META-INF/openapi.json`.

## Configuration and deployment

| Environment variable | Default / purpose |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payments` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Application login; no production password default |
| `DATABASE_MIGRATION_USER`, `DATABASE_MIGRATION_PASSWORD` | Optional separate migration credentials |
| `OIDC_URL` | Required trusted deployment issuer discovery URL |
| `OIDC_CLIENT_ID`, `OIDC_AUDIENCE` | `payment-service` |
| `PAYMENT_MAX_BODY` | `65536` bytes |
| `PAYMENT_MAX_AMOUNT` | `10000.000` JOD |
| `PAYMENT_MAX_AGE`, `PAYMENT_MAX_FUTURE` | `P30D`, `PT5M` |
| `PAYMENT_LOCK_TIMEOUT_MS`, `PAYMENT_STATEMENT_TIMEOUT_MS` | `2000`, `5000` |
| `PAYMENT_DB_ATTEMPTS` | `3`, bounded retry for deadlock/serialization/lock timeout |
| `PAYMENT_RATE_PER_MINUTE` | `6000` per authenticated subject across replicas; fixed UTC minute |
| `PAYMENT_RETENTION_DAYS` | `2555` minimum archival retention policy; never auto-deletes ledger |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` |

Quarkus properties can be overridden through their conventional uppercase environment names. Production requires TLS and disables the plaintext listener. Mount a certificate/private key and set `QUARKUS_HTTP_SSL_CERTIFICATE_FILES` and `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES`. `docker build -t payment-service:1.0 .` creates an unprivileged Java 21 runtime image. Configure external PostgreSQL and OIDC using secrets; never reuse Compose credentials in deployment. OIDC verifies RS256 signatures, expiration, issuer from discovery, configured audience, and `scope` claim values. `payments:write` submits; `payments:read` reads; `payments:admin` grants administrative access. Principal `sub` equals a customer ID or has an explicit `account_permissions` grant. Destination must allow receiving and be public-to-receive or explicitly authorized. No public mutation/admin provisioning endpoint exists.

Flyway runs versioned migrations at startup. For production, run a migration job with owner credentials, then set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` and supply only least-privilege application credentials. See `ops/OPERATIONS.md` for grants, reconciliation and recovery.

## Financial behavior

The shared parser rejects unknown/duplicate fields, typed coercion, unbounded body/depth, XML namespaces/attributes/DTD/entities/XInclude, invalid timestamps and excess decimal precision. Only purpose and notes are trimmed. Money is canonicalized to three decimal places; dates to instants; equivalent UUIDs use standard UUID representation. Null notes differs from empty notes. Decimal exponent notation is intentionally rejected; clients send ordinary decimal numbers or strings.

One transaction inserts the unique payment ID, locks account rows in lexical order, validates ownership/status/currency/receiving/daily limits and funds, performs equal debit/credit, writes two signed entries, and finalizes the immutable stored outcome. Deferred DB constraints forbid committed PROCESSING payments or unbalanced ledger; database triggers forbid ledger mutations and final payment mutations. A concurrent duplicate waits on the unique index and resolves the winner. All final business rejections are stored and replayed, with a new ID required for corrected instructions. Missing or inaccessible sources/destinations consistently return FORBIDDEN without enumerating accounts. Balance and payment lookups hide unrelated records with 404. Retrieval is limited to initiating principal or administrator.

Transient SQL failures roll back and return 503; connection/commit uncertainty is never recorded as rejection. Retry or query using the original payment ID. Replays return original receipt timestamps; request correlation headers remain unique. Field validation precedes DB access, including the configured age window. Requests older than that window must be investigated through GET rather than resubmitted. Rate limiting uses PostgreSQL so stateless replicas share the same budget. Outcomes are returned only after commit. Telemetry errors are isolated after commit.

## Troubleshooting

- `401`: verify issuer, audience, expiration, RS256 signature and bearer header.
- `403`: verify scopes and source ownership/grants, destination receiving permissions, and HTTPS.
- `503`: inspect DB readiness, lock contention, pool saturation; query payment ID before retrying.
- Docker API errors: ensure Docker supports API 1.44 and is accessible to Maven.
- Migration checksum error: add a new versioned migration; never edit a deployed migration.
- Local seeded balances persist in the Compose volume. `docker compose down` preserves them; use a fresh test database for acceptance fixtures.

See `docs/VERIFICATION.md` for measured test results and remaining environmental validation.
