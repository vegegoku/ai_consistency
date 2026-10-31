# Payment service

Java 21 / Maven / Spring Boot 3.4, PostgreSQL 16, Flyway, OAuth2 JWT resource server. The framework recommendation was treated as optional; explicit JDBC keeps financial locking and SQL constraints visible. No in-memory financial state or production authentication bypass exists.

## Verify

```sh
mvn verify
```

Requires JDK 21 and running Docker. Tests create disposable PostgreSQL containers and apply the real Flyway migrations. `src/test/resources/docker-java.properties` selects Docker API 1.44, including compatibility with recent Docker engines. No tests silently skip when Docker is unavailable. Reports: `target/surefire-reports/`. The full suite includes parsing, authenticated API requests, signatures/issuer/audience/expiry, concurrent IDs and withdrawals, rollback injection, bounded lock timeout, immutable ledger, restart durability, and corrupt-balance reconciliation.

## Local deployment

```sh
mvn package
# Includes local PostgreSQL, Keycloak realm/users, and service
docker compose up --build -d
# Or start infrastructure and run the Java process
docker compose up -d postgres keycloak
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

Local credentials are **development fixtures**: `alice` / `local-alice-only`, `bob` / `local-bob-only`; client `payments-local`. Initial accounts: `ACC-1001` belongs to `alice`, balance `500.000`; `ACC-2002` belongs to `bob`, balance `100.000`. Keycloak may take a minute to start. Production never enables the `dev` profile and must provide its own issuer/JWKS, secrets, customer records, and TLS certificate.

```sh
TOKEN=$(curl -fsS http://localhost:8081/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payments-local -d username=alice -d password=local-alice-only \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice\"}"
curl http://localhost:8080/api/v1/payments/$ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
# Use a NEW ID for another logical payment; same ID/payload is a safe replay.
ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.000</amount><date>$DATE</date><purpose>XML demo</purpose></payment>"
```

OpenAPI: `/openapi.yaml`, source `src/main/resources/static/openapi.yaml`. Health: `/actuator/health/liveness`, `/actuator/health/readiness`; metrics `/actuator/prometheus` requires `payments:admin`.

## Configuration

| Environment | Default / meaning |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Local PostgreSQL development settings; production must override |
| `OIDC_ISSUER`, `OIDC_JWKS_URI`, `OIDC_AUDIENCE` | Local Keycloak issuer / keys / `payments-api` |
| `REQUIRE_TLS` | `true`; direct HTTPS required for `/api/**`; dev sets false |
| `MAX_PAYMENT_AMOUNT` | `10000.000` JOD |
| `MAX_AGE_SECONDS`, `FUTURE_SECONDS` | `2592000`, `300` |
| `MAX_BODY_BYTES` | `65536`; oversize HTTP 413 |
| `RATE_LIMIT` | 6000 authenticated requests per principal per minute per replica |
| `TRACE_SAMPLING` | `0.1`; W3C tracing via Micrometer/OpenTelemetry bridge |
| `RETENTION_DAYS` | 2555 minimum retention; no ordinary physical deletion |
| `AUDIT_LOG_HISTORY` | 90 daily files if file logging enabled |

Native Spring `SERVER_SSL_*` properties configure TLS. Terminate TLS in the application or use a strictly trusted proxy with container-side HTTPS; the application does not blindly trust forwarded headers. Restrict liveness/readiness on the deployment network as appropriate. Scope requirements: submit `payments:write`, payment lookup `payments:read`, balance `accounts:read`, reconciliation/metrics `payments:admin`. JWT `sub` is the customer ID; production identity provisioning must keep this mapping stable. Owner debit access or explicit `account_permissions.can_debit` is required. All active destinations may receive unless `allow_receive=false`; per-source transfer limits apply. Customer blocking also prevents transfers. Payment receipts are visible only to their initiating principal. Unknown and unauthorized account transfer references receive generic `FORBIDDEN`, avoiding existence disclosure. UUIDs must use canonical lowercase hyphenated notation; account IDs match `[A-Za-z0-9_-]+`, maximum 128 characters. Purpose and notes are trimmed; null and empty notes are distinct. Amount and date are canonicalized only after strict validation, including exact 3 digit money scale and instant timestamp equivalence.

JSON numbers and decimal strings are accepted; monetary parsing uses BigDecimal. JSON/XML share `PaymentCommand` and `PaymentService.transfer`. Unknown, nested, duplicated, ambiguous or incorrectly typed values are rejected. Safe input issues are collected; business errors do not reveal balances. Malformed requests are not persisted. Valid business rejections are committed and replayed, including generic authorization refusals. Corrected instructions require a new ID.

## Financial design

The unique payment UUID insert (`ON CONFLICT DO NOTHING`) serializes concurrent duplicates. A second statement reads the committed winner under READ COMMITTED. Both account rows are locked in ascending ID order, before business checks and exact debit/credit updates. The payment, ledger and balances commit in one transaction. Final records and ledger are immutable using database triggers; deferred constraints require two correctly linked, balanced signed entries per completed payment and no entries per rejection. Missing-account rejection identifiers intentionally have no account FK; ledger uses restrictive FKs and account/customer deletions are restricted. The internal PROCESSING state cannot survive transaction commit. Opening balances anchor reconciliation.

DB lock timeout is 1.5 seconds, statement timeout 5 seconds, transaction timeout 5 seconds; at most three attempts, always preserving the ID. Transient failures roll back and return 503 after exhausted retries. An unknown commit result is resolved by querying/retrying the original ID. JSON structured outcome logs and result metrics are emitted after commit. Request correlation IDs are freshly generated, returned and logged; arbitrary client headers are not trusted. PostgreSQL is authoritative across replicas and restarts. Rate limiting is per replica; an ingress shared quota is required for a fleet-wide rate ceiling.

Flyway applies versioned migrations at startup. Production should run them with a separate migration role, then use an application role without DDL privileges; see [operations](docs/operations.md). Never run dev migrations against production. Do not edit applied migrations; add a new version.

## Troubleshooting

* Docker unavailable: start Docker, check permissions and `docker info`; test DB is mandatory.
* JDK version error: select Java 21 in `JAVA_HOME` and `PATH`.
* 401: inspect issuer, audience, signature, expiry, and stable customer subject; never print tokens in logs.
* 403: check scopes, ownership/delegation, TLS and recipient restrictions.
* 409: query the stored outcome; corrected business instructions require a new UUID.
* 503: check readiness, database connections and lock contention; use the same UUID after backoff.
* Flyway checksum mismatch: restore the original migration and create a subsequent migration.

Performance target is p95 below 500 ms at 100 requests/second. The CI `LoadTest` measures real HTTP with signed JWTs and PostgreSQL: `mvn -Dtest=LoadTest test`. It writes `target/load-report.txt`. Results depend on host and storage; run on representative deployment hardware before release. The regular CI test command remains `mvn verify`.
