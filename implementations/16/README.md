# JOD payment service

A Java 21 Maven backend with strict JSON/XML transport, OAuth bearer authentication, PostgreSQL row locks, idempotent financial effects, and immutable balanced ledger entries. Spring Boot is used instead of the recommended Quarkus stack; all funds move synchronously within one PostgreSQL transaction.

## Build and test

Install a full JDK 21 and Maven 3.9+, and start Docker. Run:

```sh
mvn verify
mvn verify -Dpayments.load=true
```

The first command executes unit, security, HTTP, concurrency, rollback, unavailable-database, lock-timeout, reconciliation and application-restart tests against disposable PostgreSQL 16. Docker is required; integration tests fail rather than silently skip when unavailable. Test-only Docker API version 1.44 is configured for recent Docker daemons. The second command also submits 1,000 signed, authenticated HTTP payments at a scheduled 100/second for 10 seconds; it checks p95 below 500 ms and writes `target/load-result.txt`. This is an engineering reference check, not a production performance guarantee. The test uses a large rate allowance and real JWT/JWKS validation.

On this workstation a full Java 21 is installed at `$HOME/.sdkman/candidates/java/21.0.5-tem`. The system Java 21 path contains only a runtime. Set both `JAVA_HOME` and `PATH` to a full JDK when Maven says release 21 is unsupported.

## Run locally

```sh
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health/readiness
TOKEN=$(curl --fail -s http://localhost:8081/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payment-service -d username=alice -d password=alice-local-only | jq -r .access_token)
PAYMENT_ID=$(uuidgen)
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\"}"
curl http://localhost:8080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

Keycloak can take several seconds to start; retry the token request once its realm is ready. Alice owns ACC-1001, initially 500.000; Bob owns ACC-2002, initially 100.000. The local seed migration is outside production migration locations. Compose credentials and HTTP allowances are only for local development.

Replay the same ID, date, amount and fields to receive 200 and the original receipt. For XML replay, using the same variables:

```sh
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose></payment>"
```

JSON is the default response type; XML responses use `<response>`. UUIDs use canonical UUID semantics; account identifiers are preserved exactly and are restricted to 1–100 letters, digits, `_` or `-`. Purpose and notes are trimmed; absent/null notes and an explicitly empty note remain distinct canonical values. Amounts reject exponent notation, coercion and excess fractional digits, including trailing zeros beyond scale 3. The fingerprint uses canonical scale, UTC instants and length-delimited UTF-8 values. Unknown or duplicate fields, XML namespaces, attributes, DTDs, entities and nested structures are rejected.

Business rejections are committed with no ledger entries and replay the original error, including its original body correlation ID and timestamp. The response header always identifies the current request. A changed instruction requires a new ID. Transient 503 errors are never stored as rejections: query status or retry the same ID. IDs belong to the initiating subject for submission replay; authorized account holders may read completed receipts. Unknown/unauthorized account information is hidden with consistent 403/404 responses.

## Configuration

Configuration uses environment variables; see `src/main/resources/application.properties`.

| Variable | Default / purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection and runtime secret |
| `MIGRATION_USER`, `MIGRATION_PASSWORD` | Separate migration owner; defaults to runtime credentials for local use |
| `JWT_ISSUER`, `JWT_JWKS_URI`, `JWT_AUDIENCE` | Trusted OIDC issuer, key endpoint, audience `payment-service` |
| `REQUIRE_TLS` | `true`; deployed requests require secure servlet transport |
| `MAX_BODY_BYTES` | `65536` |
| `MAX_PAYMENT_AMOUNT` | `10000.000` JOD |
| `FUTURE_SECONDS`, `MAX_AGE_SECONDS` | `300`, `2592000` |
| `RATE_PER_MINUTE` | `600` per authenticated subject per replica |
| `LOCK_TIMEOUT_MS`, `STATEMENT_TIMEOUT_MS`, `DB_RETRIES` | `2000`, `5000`, `3`; bounded same-ID retries |
| `DB_POOL_SIZE` | `20` |
| `AUDIT_RETENTION_DAYS` | `2555`; documented archival retention policy |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `TRACE_SAMPLE_RATE` | OTLP collector and sampling probability |

JWTs must have RS256 signatures from the trusted JWKS, matching issuer/audience, expiration and subject. The `customer_id` claim identifies account ownership. Scopes are `payments:write`, `payments:read`, `accounts:read`, and privileged `payments:admin`. An owner or subject listed in `account_debit_grants` can debit; active destination accounts must explicitly permit receipt through `can_receive`. Both accounts' configurable `max_transfer` restrictions apply.

For production, provide TLS directly with standard `SERVER_SSL_*` Spring settings or re-encrypt traffic from a trusted gateway. Forwarded headers are ignored by default. Never enable forwarded-header trust on an exposed listener. Keep JWKS endpoints on HTTPS outside the isolated local network. Apply an aggregate gateway rate limit across replicas; the built-in bounded rate limiter operates per replica. Set `RATE_PER_MINUTE` above 6000 when deliberately testing 100 requests/second from one principal.

## Database and migrations

Flyway applies versioned SQL at startup. Deploy with migration credentials distinct from the application role; `scripts/runtime-grants.sql` defines the minimum runtime privileges. Production uses only `classpath:db/migration`. For local host execution, start PostgreSQL and Keycloak through Compose and run `REQUIRE_TLS=false mvn spring-boot:run`; use `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:src/dev/resources/db/migration` to seed a fresh local database. Do not apply local V900 to production. Existing local Docker data persists across app restarts.

The payment primary key is a database idempotency guard. `INSERT ... ON CONFLICT` waits for an in-flight winner. Account rows lock in ascending identifier order. Debit, credit, ledger and final status commit together. Deferred database triggers reject unfinished or unbalanced transfers; ledger and finalized payment updates/deletes are prohibited. Payments intentionally preserve attempted account identifiers without account foreign keys so a rejection for a nonexistent account can be retained; completed account identity is enforced through ledger foreign keys and deferred matching checks. No normal API deletes financial records.

OpenAPI is served at `/openapi.yaml` and stored at `src/main/resources/static/openapi.yaml`. Health probes are `/actuator/health/liveness` and `/actuator/health/readiness`; database failure fails readiness. `/actuator/prometheus` requires the admin scope. Operational instructions are in [docs/operations.md](docs/operations.md).

## Troubleshooting

A 401 indicates a token signature, issuer, audience, expiry, or subject problem; obtain a fresh token and verify configured URLs. A 403 may mean missing scope, ownership, receiving permission, or TLS. A 409 is definitive and requires a new payment ID to correct the instruction. For 503, retain the existing ID, query the payment status and retry with backoff; do not invent a replacement ID. A Docker API error during tests indicates the daemon requires a newer API; the project pins the test client to 1.44. Flyway checksum failures require a new migration rather than edits to deployed SQL. Restore a fresh local fixture with `docker compose down -v` only when intentionally discarding all local data.
