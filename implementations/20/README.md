# Payment service 20

Java 21 / Maven / Quarkus 3.15.3 service with PostgreSQL-backed, synchronous JOD transfers. JSON and XML share a strict parser, immutable command, canonical SHA-256 fingerprint, and transactional service. Monetary arithmetic uses `BigDecimal` and `NUMERIC(19,3)`.

## Build and verify

Java 21, Maven 3.9+, and Docker are required. `mvn verify` runs unit and PostgreSQL Testcontainers integration tests and packages `target/quarkus-app/`. The tests never silently skip if Docker is unavailable. Docker API 1.44 is explicitly selected for compatibility with recent Docker daemons.

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn verify
python3 scripts/verify-packaged.py # Additional packaged HTTPS/load/restart/outage acceptance check
```

Test coverage includes JSON/XML success, equivalent cross-format replay, changed-payload conflicts, sufficient-funds checks under concurrent lock contention, duplicate races, rollback after injected debit failure, response-loss replay, authorization, malformed inputs, excess precision, XXE/DTD rejection, media negotiation, rate limiting, immutable financial rows, and corrupted-balance reconciliation. Database state is read on fresh connections, never reconstructed from process memory.

## Local HTTPS deployment

The checked-in Compose configuration and generated signing keys are development-only. Start from this directory:

```sh
./scripts/dev-setup.sh
docker compose up --build -d
TOKEN=$(python3 scripts/dev-token.py alice)
ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl --cacert deploy/tls/cert.pem https://localhost:8443/q/health/ready
curl --cacert deploy/tls/cert.pem -i https://localhost:8443/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice\"}"
curl --cacert deploy/tls/cert.pem https://localhost:8443/api/v1/payments/$ID -H "Authorization: Bearer $TOKEN"
curl --cacert deploy/tls/cert.pem https://localhost:8443/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

Send XML with a **new** payment ID to make a second transfer, or the same ID and equivalent fields to replay:

```sh
curl --cacert deploy/tls/cert.pem https://localhost:8443/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice</purpose></payment>"
```

Seed: Alice owns `ACC-1001` with `500.000 JOD`; Bob owns `ACC-2002` with `100.000 JOD`. `python3 scripts/dev-token.py bob` creates a Bob token; `admin` creates a privileged development token. The private JWT key stays outside the app container. The optional startup seed is enabled only in dev and explicit local Compose configuration (`PAYMENTS_SEED_DEMO=true`); it inserts missing demo rows without resetting balances. It is disabled in production by default.

JSON numeric and string decimal amounts are accepted without conversion through floating point. Excess scale, exponent notation, unknown fields, duplicate keys/elements, XML attributes/namespaces/nesting, DTDs and entity references are rejected. Strings preserve account and UUID spelling; canonical UUIDs are case insensitive, purpose/notes are trimmed, equivalent amounts are padded to three decimal places, and date offsets normalize to an instant. Missing notes and explicit null notes normalize to null; empty notes remain empty. Output XML uses a `<response>` root; error lists use `<issues><issue>…`.

## Authorization and configuration

JWT bearer signature (RS256), issuer, audience, and expiration are validated by SmallRye JWT. Required claims: `sub`, trusted `customer_id`, and space-separated `scope`. API scopes are `payments:write`, `payments:read`, and `accounts:read`. Administrators additionally need `admin` in `groups` and `payments:admin` to reconcile. Owner or explicit `account_permissions.can_debit` grants may debit an account. `can_view` grants balance visibility; payment lookup requires initiating principal or admin. All active `receive_enabled` accounts permit incoming internal payments; disabling the flag is the destination restriction. Authentication claims are only trusted after issuer/signature verification. Unknown/unauthorized source or destination accounts return a generic forbidden response. Unknown/hidden payment lookups return 404.

| Variable | Default / purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Local JDBC configuration; provide managed secrets in production |
| `JWT_PUBLIC_KEY` | PEM file URL or trusted HTTPS JWKS location; required deployment key |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Exact trusted issuer and audience |
| `PAYMENT_BODY_LIMIT` | `65536` bytes, enforced before parsing |
| `PAYMENTS_SEED_DEMO` | `false`; explicit opt-in local data only |
| `PAYMENT_MAXIMUM` | `10000.000` JOD; validation maximum |
| `PAYMENT_FUTURE_SECONDS`, `PAYMENT_OLD_SECONDS` | `300`, `2592000` |
| `PAYMENT_RATE_PER_MINUTE` | `600` per principal; shared PostgreSQL buckets |
| `PAYMENT_RETENTION_DAYS` | `2555`; operational archive review horizon; no automatic financial deletion |
| `QUARKUS_HTTP_SSL_CERTIFICATE_FILES`, `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES` | Mandatory production TLS certificate and key |
| `OTEL_DISABLED` | `true`; set `false` with `OTEL_EXPORTER_OTLP_ENDPOINT` for tracing |

The default deployed profile disables cleartext HTTP. Bind health/metrics/admin routes to a restricted network at ingress. CORS is not enabled. Do not trust client correlation headers by default; trusted ingress may set `PAYMENTS_TRUST_CORRELATION_HEADER=true` and supply UUID `X-Correlation-ID` values. Responses include a correlation header, `Cache-Control: no-store`, and error correlation IDs.

DB connection acquisition is bounded to 5 seconds; connect 5 seconds, socket 10 seconds; transfer lock timeout 2 seconds and statement timeout 5 seconds. Serialization/deadlock failures retry at most twice with the same ID; other timeouts/unavailability return 503 with `Retry-After`. Rate limits return 429. Every logical instruction has a database-unique UUID. PostgreSQL conflict waits safely serialize concurrent duplicates; deterministic account row locks prevent overdrafts and deadlocks.

## Schema, API and operations

Flyway executes `src/main/resources/db/migration` on startup; production uses V1 schema and V3 shared rate-limit buckets. Local seed data is separate and explicitly enabled; all schema changes use Flyway. Back up before schema upgrades; run one migration job before rolling replicas in production. Use a migration owner distinct from a runtime user. Grant runtime `SELECT,INSERT,UPDATE` only on accounts, payments and request rates, `SELECT` on customer/permission tables, and `SELECT,INSERT` on ledger entries plus sequence usage; do not grant DDL or financial DELETE. Database triggers reject final-payment mutation and ledger updates/deletes, and a deferred constraint trigger checks each completed payment has two balanced entries before commit.

Full JSON/XML contracts and all errors are in `src/main/resources/META-INF/openapi.yaml`, served at `/q/openapi`. Health: `/q/health/live`, `/q/health/ready`; readiness includes datasource connectivity. Prometheus: `/q/metrics`. Structured JSON logs contain correlation ID, validated payment ID, HTTP result, and latency without account identifiers or request payloads. Metrics count HTTP outcomes, duration, committed/rejected outcomes, idempotent replays, retries, database timeouts/unavailability and unexpected failures. OpenTelemetry instruments requests/JDBC when enabled. Financial receipts and rejection records include principal and correlation ID in durable storage.

See [operations](docs/operations.md) for reconciliation, retention, backup/restore and incident response. See [verification](docs/verification.md) for measured results and limitations.

## Troubleshooting

- Java release error: select a Java 21 JDK before Maven.
- Docker tests fail: verify `docker info` and socket permissions. Tests set Docker API 1.44 because older docker-java defaults are rejected by new daemons.
- 401: verify issuer, audience, expiration, RS256 signature and `JWT_PUBLIC_KEY`; local tokens expire in 15 minutes.
- 403/404: verify subject/customer and account permissions; responses intentionally hide unrelated records.
- 503: inspect readiness, pool capacity and PostgreSQL locks. Query payment status or retry the **same** UUID; never invent a new UUID to resolve uncertain commit outcome.
- Flyway validation errors: do not edit applied migrations; add a versioned migration. Demo seeding must not be enabled with production data.
- TLS startup error: supply certificate/key paths or use `mvn quarkus:dev` for explicit local cleartext development.
