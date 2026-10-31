# Payment service

Java 21, Maven, Spring Boot 3.4, PostgreSQL 16. The requirements recommend Quarkus; this implementation uses Spring's explicit JDBC transactions and OAuth2 resource server to implement the same synchronous API. All amounts use `BigDecimal` and PostgreSQL `NUMERIC(19,3)`.

## Verify

Install a **JDK** 21 (a JRE alone is insufficient), Maven 3.9+, and Docker with access to its socket:

```sh
mvn verify
```

Tests launch real ephemeral PostgreSQL containers. The suite covers strict JSON/XML parsing, decimal precision, authorization, signed JWT signature/issuer/audience/expiry, successful transfers, original receipts on retry, changed-payload conflicts, durable rejections, concurrent identical and competing payments, atomic failure after debit, bounded lock timeout, database ledger invariants, reconciliation corruption, and forced JVM restart with the packaged service. Tests fail if Docker is unavailable; no database tests are silently skipped. `docker-java.properties` selects API 1.44 for modern Docker daemons.

The executable artifact is `target/payment-service-1.0.0.jar`. CI runs the same command. Build the nonroot container with `docker build -t payment-service .` (the Docker build packages without running Docker-dependent tests; run verification first).

## Local setup

Use local-only credentials and loopback bindings:

```sh
export DB_PASSWORD='replace-with-local-only-password'
docker compose up -d
export OIDC_ISSUER=http://localhost:8081/realms/payments
export OIDC_AUDIENCE=payments
export REQUIRE_TLS=false
mvn spring-boot:run
```

Flyway applies versioned migrations at startup. In another terminal, after application startup:

```sh
docker compose exec -T db psql -U payments -d payments < docs/dev-seed.sql
```

The imported development Keycloak realm provides Alice and Bob, password `local-demo-only-change-me`, and the public client `payment-cli`. These development fixtures are never automatically loaded into production. Request Alice's token:

```sh
TOKEN=$(curl -fsS http://localhost:8081/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payment-cli -d username=alice \
  -d password=local-demo-only-change-me | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
PAYMENT_ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October services\"}"
curl -fsS "http://localhost:8080/api/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $TOKEN"
curl -fsS http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

Equivalent XML (use a new ID for a new logical payment; the same ID and fields replay the original):

```sh
curl -i http://localhost:8080/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose><notes>October services</notes></payment>"
```

`docs/openapi.json` is the complete OpenAPI contract. Errors use one envelope with correlation ID and timestamp; business rejections retain their original envelope. Hidden accounts and hidden payments return 404 on lookup. Unknown/unauthorized source or unavailable destination yields a generic 403 on submission; existence is not disclosed. Amount replies always contain exactly three decimal places. Initial completion returns 201 and Location, replay 200, persisted rejection its original 403/409, and transient uncertainty 503 with Retry-After. A timeout or lost response must be resolved using the **same payment ID**.

## Canonicalization and permissions

JSON numbers or decimal strings and XML text map to one immutable command. Decimal and scientific notation are parsed exactly; effective precision above 3 is rejected even for trailing zeros. IDs are never trimmed or rewritten: payment IDs must be canonical lowercase UUIDs; account IDs are nonblank strings of at most 128 characters and preserve all characters verbatim. Purpose and notes are stripped of surrounding Unicode whitespace; absent notes canonicalize to the empty string. Timestamps require offsets and fingerprint as UTC instants. Amount fingerprints use scale 3. Unknown fields, duplicate names, null or incorrect typed values, XML namespaces/attributes, nested elements, DTDs/entities and trailing documents are rejected. The parser never accesses external resources.

JWTs require a valid signature, issuer, payment audience, expiration and nonempty subject. Scopes: `payments:write` for submission, `payments:read` for receipt lookup, `accounts:read` for balances, `payments:admin` for reconciliation and Prometheus. The JWT subject must match `customers.id`, or an explicit `account_debit_grants` row must authorize debiting. Destination must be active and receive-enabled. Every principal with debit authority may transfer to receive-enabled accounts. Lookup is limited to the initiator or source/destination owners; granted debit principals may inquire about their granted account. Idempotent submission is restricted to its original initiator.

## Configuration

| Environment variable | Default / purpose |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payments` |
| `DB_USER`, `DB_PASSWORD` | `payments`, required password |
| `DB_POOL_SIZE` | 20 |
| `OIDC_ISSUER`, `OIDC_AUDIENCE` | required issuer; `payments` |
| `REQUIRE_TLS` | `true`; HTTP business endpoints forbidden unless explicitly disabled for local development |
| `MAX_BODY_BYTES` | 65536 |
| `MAX_PAYMENT_AMOUNT` | 10000.000 |
| `MAX_FUTURE_SECONDS`, `MAX_AGE_SECONDS` | 300, 2592000 |
| `DAILY_TRANSFER_LIMIT` | 50000.000; UTC day, reduced by account-specific daily_limit |
| `RATE_PER_MINUTE` | 600 per authenticated principal per instance; applies to all business endpoints |
| `RETENTION_DAYS` | 2555; retention review horizon, never automatic financial deletion |
| `TRACING_ENABLED` | false locally; enable in production |
| `TRACE_SAMPLE_PROBABILITY` | 0.1 |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` |
| `PORT` | 8080 |

Database row waits are bounded at 1.5 seconds, statement execution at 5 seconds in transfer transactions and 10 seconds on connections, pool acquisition at 3 seconds, and transient retries at 2 beyond the initial attempt. A persistent lock therefore fails safely after bounded retries. Transport connections have a 5-second timeout; deploy gateway request-read deadlines and body limits as well.

Run HTTPS directly using Spring `SERVER_SSL_ENABLED`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD` and `SERVER_SSL_KEY_STORE_TYPE`. Untrusted forwarding headers are ignored. For ingress termination, secure the app network, configure a trusted forwarding mechanism explicitly and retain TLS enforcement; do not globally trust Internet-supplied forwarding headers. Production requires a managed PostgreSQL deployment, distinct migration/runtime credentials, secret injection, persistent backups and gateway rate limits shared across replicas. Application rate limiting is deliberately an additional per-instance bound.

## Design and operational behavior

A unique PostgreSQL payment key arbitrates duplicates; `INSERT ... ON CONFLICT` waits for the winner. Both account rows lock in ascending ID order. All policy/funds checks happen while locked, then both balances, two ledger entries and the final outcome commit together. PENDING exists only inside the transaction; deferred constraints prohibit its commit. Terminal payment records and all ledger entries reject update/delete. Rejected records preserve attempted account IDs without foreign keys so unknown-account rejections can be durable; completed ledger account references have restrictive foreign keys. Daily-limit queries serialize under the source lock. Opening balances establish reconciliation baselines.

Metrics and structured completion/rejection logs run after commit; telemetry failures cannot roll back or replace a completed financial result. Logs exclude tokens, account IDs, principal identities, raw messages and SQL. Durable payment records identify their initiator and original correlation UUID. Correlation IDs are server-generated because this deployment has no configured trusted-header boundary. The current response returns `X-Correlation-ID`; replay rejection envelopes retain the original audit correlation ID.

Liveness: `/actuator/health/liveness`; DB-aware readiness: `/actuator/health/readiness`; scoped metrics: `/actuator/prometheus`. Micrometer exports requests, outcomes by status/error, processing latency, replay count, retry count, lock timeouts, errors and unexpected failures. OpenTelemetry tracing can export HTTP observations when enabled.

Use `scripts/load.py` for an offered rate of 100 requests/second with a funded fixture and appropriately scoped token. It writes counts and p95 to a JSON report and checks the initial 500 ms target. This is a measurement, not a production capacity guarantee; record CPU, memory, storage, network and instance count alongside every result. See `docs/OPERATIONS.md` for reconciliation, retention, backup/restore, deployment and incident response.

## Troubleshooting

* `release version 21 not supported`: use a full JDK 21 and confirm `mvn -version`; some system Java directories contain only a JRE.
* Docker/Testcontainers failure: verify `docker info`, socket permissions and Docker API compatibility. Never replace PostgreSQL concurrency tests with an in-memory database.
* 401: check signature key discovery, issuer URL, `aud=payments`, expiry and token scope. Development Keycloak can take time to start.
* 403: check debit ownership/grants, destination receive settings, scopes and actual HTTPS transport.
* 400: inspect all returned field issues; request dates must be within the configured window.
* 503: check database readiness/connection pool and lock contention. Query the original payment ID before retry; do not generate a new ID.
* Flyway checksum mismatch: add a new migration; never edit an already-deployed migration. Coordinate schema changes before rolling application replicas.
