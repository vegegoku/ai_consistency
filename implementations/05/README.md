# Payment service 05

Java 21 / Quarkus 3.15 / Maven service implementing the copied payment processing specification. PostgreSQL is the only supported financial database. JSON and securely parsed XML share the same immutable command and transactional service. Money uses BigDecimal and NUMERIC(19,3).

## Build and run

Install JDK 21, Maven 3.9+, Docker and Compose. `mvn verify` runs parser and real PostgreSQL tests via Quarkus Dev Services/Testcontainers and builds `target/quarkus-app`. Docker must be available; tests are not silently skipped without Docker. The build configures Docker API 1.44 for Docker 29 compatibility.

```sh
docker compose up -d
TOKEN=$(python3 scripts/dev-identity.py)
export JWT_ISSUER=https://local.payment.invalid
export JWT_PUBLIC_KEY_LOCATION="file:$(pwd)/.local/public.pem"
mvn quarkus:dev
# In another terminal, after the service starts and runs Flyway:
docker compose exec -T postgres psql -U payments -d payments < ops/dev-seed.sql
TOKEN=$(python3 scripts/dev-identity.py)
```

Local credentials and generated identity are development only. Production never seeds data or generates signing keys. Dev mode permits HTTP on loopback; production disables HTTP and requires TLS. Configure `QUARKUS_HTTP_SSL_CERTIFICATE_FILES`, `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES` and expose port 8443. The production IdP must issue RS256 JWTs with trusted issuer, audience, subject, expiration, and `scope` containing `payments:write`, `payments:read`, or privileged `payments:admin`. JWT groups with matching permission names are also accepted. Source ownership is linked to the authenticated principal; explicit delegations live in `account_debit_grants`. The destination must exist, be ACTIVE, and allow incoming funds. Absent or inaccessible transfer accounts use a generic FORBIDDEN error to avoid enumeration. Lookups conceal inaccessible resources with 404.

```sh
ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
 -d "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice\"}"
curl http://localhost:8080/api/v1/payments/$ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
# Equivalent XML instruction with a NEW ID transfers again; the same ID and identical fields replay.
curl http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 -d "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice</purpose></payment>"
```

First completion returns 201 + Location; identical replays return 200 and original receipt. Business rejections remain retrievable and replay with their original HTTP code/body. Different canonical payloads return 409. Canonical content trims purpose/notes, represents absent notes as empty, normalizes amount to scale 3 and offset timestamps to UTC. Account identifiers are never trimmed. Unknown/duplicate fields, non-scalar values, XML attributes/namespaces/nesting/DTD/entities, and trailing documents are rejected. Unsupported input/output types return 415/406. `X-Correlation-ID` accepts a UUID-shaped header or generates one; deployment gateways should strip untrusted inbound correlation headers if their origin matters.

## Configuration

| Environment | Default / purpose |
|---|---|
| DB_URL | jdbc:postgresql://localhost:5435/payments |
| DB_USER / DB_PASSWORD | payments / payments-local-only, override from secrets in deployment |
| JWT_ISSUER / JWT_AUDIENCE | trusted issuer / payment-service |
| JWT_PUBLIC_KEY_LOCATION | trusted HTTPS JWKS URL or file public key |
| PAYMENT_MAXIMUM | 10000.000 JOD |
| PAYMENT_MAX_FUTURE_SECONDS / PAYMENT_MAX_AGE_SECONDS | 300 / 2592000 |
| PAYMENT_BODY_LIMIT | 65536; also set QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE consistently |
| PAYMENT_RATE_LIMIT | 600 requests/principal/minute, shared across replicas in PostgreSQL |
| PAYMENT_RETENTION_DAYS | 2555; policy floor for controlled financial archives, see operations |
| OTEL_ENDPOINT | http://localhost:4317 OTLP collector |

Operational transfer limits use each account's `transfer_limit`; account status and `allow_incoming` are enforced under lock. All payments use JOD. DB pool maximum is 30, acquisition 5s, connect 5s, socket 15s; transaction timeout 15s; row locks 2s and statements 5s. Deadlock, serialization and lock timeout errors retry at most 3 attempts using the same ID; exhaustion or database failure returns retryable 503. Request rate limiting fails closed when its DB is unavailable. Schema Flyway migrations run at startup; use a migration identity then disable startup migrations (`QUARKUS_FLYWAY_MIGRATE_AT_START=false`) for the runtime identity with grants in `ops/least-privilege.sql`.

OpenAPI: `/q/openapi` (static contract `src/main/resources/META-INF/openapi.yaml`); liveness `/q/health/live`; DB readiness `/q/health/ready`; Prometheus `/q/metrics`. Keep management routes behind private network policies. Metrics cover HTTP count/latency, payment completion/rejection, replay, retries, lock timeout, unexpected errors. Structured logs include correlation/payment identity, status/code and latency, never tokens or raw instructions. See [operations](docs/operations.md).

## Verification and troubleshooting

`mvn verify` covers exact balances, balanced ledger, durable rejection, replay/conflict, parallel duplicate IDs, concurrent spending, transaction rollback after debit, API JSON/XML negotiation, caller access, malformed documents and XXE. The rollback seam is an injectable transaction-boundary guard, with failure enabled only by a test alternative. Fresh DB connections verify committed state and replay after a discarded response models response loss. Reconciliation tests deliberately corrupt the balance fixture. Production DB constraints prohibit ordinary mutation of financial history.

A missing Docker socket fails integration startup. Docker 29 requires an API version >=1.40; Maven sets 1.44. A 401 means inspect issuer/audience/expiration/signature and scope; never log the token. A 503 means query/retry using the SAME ID and inspect DB availability and lock metrics. A migration checksum failure requires a new versioned migration, never editing deployed migrations. See test evidence in `target/surefire-reports`.

A 100 req/s, p95 <500ms load harness is supplied in `scripts/load.js`. Before running, grant the load principal debit access to both test accounts, set PAYMENT_RATE_LIMIT above 6000/min, run an isolated funded fixture, and use `k6 run -e TOKEN=... -e BASE_URL=https://... scripts/load.js`. Store hardware/replica/DB details with results. This performance target is not claimed without a representative measurement. Run `python3 scripts/restart-smoke.py` after building for an automated isolated PostgreSQL + packaged TLS/JWT process restart/response-loss test; `LOAD_SECONDS=60 python3 scripts/restart-smoke.py` additionally measures 100 requests/sec and writes `docs/load-results.json`. The same check runs in CI. Backup recovery drills and retention approvals are deployment operational responsibilities, with procedures below.

## Recorded verification

The delivered implementation passed `mvn verify` on Java 21 against an isolated PostgreSQL 16 container: 18 tests, zero failures/errors/skips. The packaged production-mode service also passed an actual TLS/JWT stop/start durability test and a 60-second 100 requests/sec run: 6,000 payments, zero failures, p95 21.65 ms, zero reconciliation issues. See [load evidence](docs/load-results.json). This is shared-host engineering evidence, not a production latency guarantee. The load test used PAYMENT_RATE_LIMIT=20000 and one service process; production failover and backup restore drills remain operator checks.
