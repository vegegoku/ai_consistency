# JOD Payment Service — implementation 13

Java 21 / Maven / Quarkus backend for synchronous internal JOD transfers. PostgreSQL is the source of truth for balances, immutable receipts, rejections, and the double-entry ledger. JSON and hardened XML enter one immutable domain command and one transfer use case.

## Build and verify

Prerequisites: JDK 21, Maven 3.9+, Docker Engine. Run from this directory:

```sh
mvn -B verify
```

The suite starts isolated PostgreSQL 16 Testcontainers; it does not use H2 or silently skip database tests. It covers exact accounting, both formats, duplicate keys/elements, XML security, validation, JWT/scopes/ownership, durable replay/rejection, conflict, concurrent duplicates and overdraft prevention, failure after debit, immutable ledger, and corrupted reconciliation fixtures. `src/test/resources/docker-java.properties` pins a Docker API supported by modern Docker engines.

## Run locally

```sh
docker compose up -d --wait db
export DB_URL=jdbc:postgresql://localhost:54313/payments
export DB_USERNAME=payments DB_PASSWORD=local-only-payments
mvn quarkus:dev
```

The dev profile migrates the schema and seeds Alice's `ACC-1001` with `500.000 JOD`, and Bob's `ACC-2002` with `100.000 JOD`. Both are active and allow incoming payments. Production never runs the development seed migration. The bundled development RSA signing key is public test material and is never trusted by the production profile.

Alternatively, `docker compose --profile service up --build -d` starts the packaged service on `http://localhost:18013` using the explicit local profile. Compose binds ports to loopback and persists the DB in a named volume. `docker compose down` preserves data; deleting the volume is a deliberate local reset.

```sh
export TOKEN=$(python3 scripts/dev-token.py alice)
export ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
export DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice 2048\"}"
curl http://localhost:8080/api/v1/payments/$ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

XML example (use a **new** ID for a new logical payment):

```sh
ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.250</amount><date>$DATE</date><purpose>Invoice</purpose></payment>"
```

## Contract and authorization

- `POST /api/v1/payments`: scope `payments:write`; first commit is 201, same canonical instruction is 200, changed instruction is 409. A business rejection is durable and replays its original code, correlation ID, and timestamp.
- `GET /api/v1/payments/{paymentId}`: scope `payments:read`; only the original initiating subject can see a receipt. Unknown and invisible IDs return 404.
- `GET /api/v1/accounts/{accountId}/balance`: scope `payments:read`; owner or explicit debit delegate only.
- `GET /api/v1/admin/reconciliation`: scope `payments:admin`; reports financial discrepancies and configured retention policy.

JWT `sub` maps to customer ID or explicit `account_permissions.principal`; ownership is never inferred from the submitted account identifier. Scopes come from the signed `scope` claim. Destination policy requires `public_receive`, ownership, or explicit `can_receive`, plus `receive_enabled`. Frozen/closed accounts reject. Missing/invisible accounts during transfer use the same 403 to avoid enumeration.

Only `purpose` and `notes` are trimmed; absent/null notes normalize to empty text. Amount representations `125.75`, `"125.750"` normalize to the same exact scale-3 value. Offset timestamps normalize to an instant. Identifiers are not trimmed. Decimal exponents are rejected. JSON duplicate/unknown fields, invalid scalar types, XML duplicates/namespaces/attributes/nested fields, DTD/entities and external resources are rejected. The XML reader accepts only two element levels.

The service supports HTTP/1.1; HTTP/2 is disabled because this Quarkus release cannot pass an already bounded body into its REST stream adapter on HTTP/2. Responses default to JSON; XML is negotiated by `Accept`. Other response formats return 406, other request types 415. Errors have a stable safe contract. A new server-generated correlation UUID is returned in `X-Correlation-ID`; arbitrary inbound IDs are untrusted and ignored. Balances and full instruction payloads are absent from logs.

The static specification is `src/main/resources/META-INF/openapi.json`, served at `/q/openapi`. Health: `/q/health/live`, `/q/health/ready` (includes database). Prometheus: `/q/metrics`.

## Configuration and deployment

| Environment variable | Default / purpose |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL JDBC connection and secret |
| `JWT_PUBLIC_KEY_LOCATION` | Production public key file or HTTPS JWKS URL; production default fails closed |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Exact expected issuer and `payment-service` audience |
| `TLS_CERT_FILE`, `TLS_KEY_FILE` | Production TLS PEM files; plain HTTP disabled in production |
| `PAYMENT_MAX_AMOUNT` | `10000.000` JOD |
| `PAYMENT_MAX_FUTURE_SECONDS` | `300` |
| `PAYMENT_MAX_AGE_SECONDS` | `2592000` (30 days) |
| `PAYMENT_MAX_BODY_BYTES` | `65536`, HTTP and parser limit |
| `PAYMENT_RATE_LIMIT_PER_MINUTE` | `6000` per authenticated subject across replicas |
| `PAYMENT_RETENTION_MINIMUM_YEARS` | `7`; documented minimum archival policy |
| `PAYMENT_AUDIT_LOG_RETENTION_DAYS` | `90`; log collector retention policy |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317`; tracing collector |

Build an image with `docker build -t payment-service:1 .`. Mount TLS keys and the production JWT verification key read-only and supply DB credentials through your secret manager. The image runs as UID 10001. Use the production profile; `local` and `dev` are explicitly development only. Restrict health/metrics/OpenAPI to the operations network at the ingress. Use JDBC `sslmode=verify-full` and a trusted certificate for remote production PostgreSQL.

Flyway applies versioned migrations at startup by default for local convenience. Production should run one migration job with a schema-owner identity, then set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` and use the runtime grants in [operations](docs/OPERATIONS.md). Do not edit a deployed migration; add a new version.

## Transaction design

A database transaction takes a bounded advisory lock on the payment UUID, checks the stored identity and fingerprint, and inserts under the payment primary key. Both accounts lock in ascending ID order. Authorization, policy, account state, and funds are checked under lock. Both exact balance updates and both ledger entries commit together. A deferred DB constraint checks completed ledger count, signs, amount, and accounts. No `PROCESSING` row can commit. Ledger rows and final payments reject mutation. SQLSTATE deadlock/serialization/timeout errors have up to three attempts under the same payment ID. Lock waits are 2 seconds; statements 5 seconds; connection waits are bounded. All other failures roll back; infrastructure errors never become durable business rejections.

Retries after ambiguous network/commit failures use the **same ID and payload**, or query status. A new service instance reads the original database outcome. Post-commit metrics/log failures cannot change a committed transfer.

## Performance

On an isolated seeded local database:

```sh
PAYMENT_TOKEN=$(python3 scripts/dev-token.py alice) LOAD_SECONDS=30 python3 scripts/load.py
```

The open-loop test offers 100 requests/s, transfers 0.001 JOD, reports p95 and errors, and exits nonzero when errors occur or p95 is at least 500 ms. Use `PAYMENT_URL=http://localhost:18013` for Compose. This is a reference measurement, not an availability guarantee; retain hardware, concurrency, database, and telemetry configuration with results. Never run this script on production or a shared financial fixture.

## Troubleshooting

- JDK mismatch: `java -version` must report 21; set `JAVA_HOME` and `PATH` consistently.
- Docker failure: run `docker info`; tests require access to the daemon and image registry.
- 401: check signature/key, issuer, audience, `sub`, `iat`, `exp`, and `scope`. Development tokens last 15 minutes.
- 403: check scope and persisted ownership/delegation/destination policy. Unknown accounts intentionally do not disclose existence.
- 409: a definitive business rejection requires a new ID for corrected instructions; a payload conflict requires the original payload or a new logical payment ID.
- 503: query the payment ID or retry the unchanged instruction; inspect DB readiness, lock waits, and pool saturation. Never assume a timed-out transfer did not commit.
- Migration mismatch: restore the expected migration files and use a new version; never clean production data to fix a checksum.

See [operations](docs/OPERATIONS.md) for reconciliation, backup/restore, retention, and incident handling.

Verification record: [acceptance evidence](docs/VERIFICATION.md) and [measured load result](docs/load-result.json).
