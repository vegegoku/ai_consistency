# Payment service

Java 21, Maven, PostgreSQL 16. A stateless synchronous HTTP service using JDK HTTP/HTTPS, Jackson streaming JSON, hardened StAX XML, JDBC/HikariCP, Flyway, Nimbus RS256 JWTs, Micrometer Prometheus, and OpenTelemetry. The specification recommends Quarkus; this implementation uses the JDK transport to keep strict wire parsing and transaction boundaries explicit. Every response follows the versioned API contract.

## Build and verify

A full **JDK 21** and Docker daemon are required. Run `mvn verify`. Tests start PostgreSQL 16 in Testcontainers and fail if Docker is unavailable; they never substitute an in-memory database or silently skip database tests. Docker API version 1.44 is selected for compatibility with newer daemons. On this workspace use:

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem mvn verify
```

The shaded runnable artifact is `target/payments-1.0.0.jar`. CI runs the same verification command. Tests cover wire parsing, XML security, JWT claims/signatures, authenticated REST behavior, account access, transaction concurrency, exact amounts, replay/conflict, rejection persistence, rollback injection, immutable records and reconciliation.

## Local development

```sh
TOKEN=$(python3 scripts/dev-token.py alice)
docker compose up --build -d
docker compose exec -T postgres psql -U payments -d payments < scripts/seed.sql
curl http://localhost:8014/health/ready
```

Compose binds development HTTP and PostgreSQL only to loopback. Local credentials and generated signing keys are development fixtures. The private key is ignored by Git and is never used by the service, which reads only the public JWKS. Tokens last one hour; rerun the token script after expiry. Bob can read his own balance with a separately generated `bob` token but cannot debit Alice.

```sh
ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8014/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
 --data "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice payment\"}"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8014/api/v1/payments/$ID
curl -H "Authorization: Bearer $TOKEN" http://localhost:8014/api/v1/accounts/ACC-1001/balance
# Same logical payment, XML retry returns the original receipt with HTTP 200:
curl -i http://localhost:8014/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 --data "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice payment</purpose></payment>"
```

For running without Compose, configure DB_URL as `jdbc:postgresql://localhost:5414/payments`, DB_USER=payments, DB_PASSWORD=local-dev-only, DEV_MODE=true, JWT_ISSUER=https://local.example, JWT_AUDIENCE=payments and JWT_JWKS_FILE=config/public-jwks.json, then `java -jar target/payments-1.0.0.jar`.

## Configuration

| Environment variable | Default / behavior |
| --- | --- |
| DB_URL, DB_USER, DB_PASSWORD | localhost PostgreSQL, payments user, empty password; set secrets externally |
| PORT | 8080 |
| DEV_MODE | false; HTTPS mandatory unless explicitly enabled for local development |
| TLS_KEYSTORE, TLS_PASSWORD | PKCS12 certificate/key and its secret; required outside development |
| MIGRATE_ON_START | true only in DEV_MODE; production migrations run separately |
| JWT_ISSUER, JWT_AUDIENCE | issuer and audience validated on every token |
| JWT_JWKS_FILE | config/public-jwks.json; public RSA >=2048-bit keys, kid required, RS256 only |
| MAX_BODY_BYTES | 65536; bounds bytes before parse; excess returns 413 PAYLOAD_TOO_LARGE |
| MAX_PAYMENT_AMOUNT | 10000.000 JOD |
| MAX_AGE_DAYS, FUTURE_SKEW_SECONDS | 30 days / 300 seconds for new IDs |
| RATE_LIMIT_PER_MINUTE | 600 authenticated requests per principal per replica |
| AUDIT_RETENTION_DAYS | 2555 days (seven years); documented retention review boundary, never automatic financial deletion |
| OTEL_TRACES_EXPORTER | none by default; set otlp to export spans |
| OTEL_EXPORTER_OTLP_ENDPOINT | configured collector URL when exporting |

Scopes are `payments:submit`, `payments:read`, and privileged `payments:admin`. Source owner maps to JWT subject via `customers.id`; explicit delegations live in `account_debit_grants`. Source/destination must be active, customer active, destination receiving allowed. Per-account daily debit limits and optional destination allowlists are enforced under the same row lock. Daily limits reset at UTC midnight. Rate limits are per replica; apply a shared gateway quota for a deployment-wide limit. Untrusted caller correlation headers are ignored: the service generates and returns X-Correlation-ID.

Only purpose/notes are trimmed; missing/null/empty notes canonically become empty. Account IDs are never trimmed. UUID input must use the full 8-4-4-4-12 format, numeric amount or decimal string is accepted, fractional scale above three is rejected even when extra digits are zeros. Amount is padded to three decimal places. Offset timestamps canonicalize to UTC instants. Retry lookup precedes timestamp window checking so historical completed instructions remain replayable. JSON and XML must have exactly the specified fields. XML has one `payment` root, at most two element levels, no attributes/namespaces/DTD/entities/XInclude; JSON has one root and rejects trailing documents, unknown keys and duplicate keys. XML responses use a `response` root.

## Atomicity and operational behavior

Each transfer obtains a PostgreSQL transaction advisory lock derived from the UUID, then looks up the immutable stored result. Account rows lock in ascending ID order. All debit/credit, result and paired signed ledger entries commit together. A primary key additionally enforces payment uniqueness. Deferred database triggers enforce exactly two correctly signed/account-linked entries for completed payments and none for rejected payments. Updates/deletions of payment and ledger records are prohibited by triggers. Opening balance is the reconciliation baseline; runtime SQL grants cannot change it.

A duplicate ID submitted by a different principal gets 403, without exposing its receipt. Identical replay returns 200 for success and the original HTTP status/body for rejection. Changed payload returns 409. A durable business rejection consumes its ID. Malformed or field-invalid messages do not. Infrastructure failures roll back and are not recorded as business rejections. Unknown commit outcomes require querying or retrying the same ID; never generate a replacement ID automatically.

The per-attempt lock timeout is 1.5 seconds and statement timeout is 4 seconds. Deadlock/serialization/lock timeout retries are bounded to three attempts with small backoff. DB connection acquisition is bounded to three seconds, network connect three seconds and socket ten seconds. HTTP read/write deadlines are ten seconds and 256 concurrent handlers are admitted; additional requests get 503 and Retry-After. No access tokens, request bodies, balance values or account identifiers appear in service event logs. Structured events include correlation/payment IDs, outcome/error code and latency. Audit initiator is persisted with the payment.

The OpenAPI contract is in `src/main/resources/openapi.yaml` and served at `/openapi.yaml`. Health endpoints are `/health/live` and `/health/ready`; readiness checks PostgreSQL. `/metrics` and `/api/v1/admin/reconciliation` require the admin scope. Metrics cover requests, result codes, latency, replay, retries, lock timeouts and unexpected failures. Spans accept and propagate W3C trace context without recording request payloads or financial details.

## Migrations and production

Use a separate migration-owner credential to run:

```sh
java -cp target/payments-1.0.0.jar com.example.payments.persistence.Migrate
```

Flyway validates checksums and applies versioned SQL. Provision a runtime role through secret management, then run `scripts/runtime-grants.sql` as the owner. Deploy replicas with MIGRATE_ON_START=false, that runtime credential, HTTPS PKCS12 secrets, a trusted public-only issuer JWKS, and production issuer/audience. Rotate JWKS by mounting old and new public keys and rolling replicas; never accept private JWKs. Restrict database network access and management endpoints at the network edge. Financial rows cannot be deleted by ordinary service operations.

## Performance and troubleshooting

`scripts/load.js` is a k6 constant-arrival-rate test at 100 requests/second for 60 seconds with p95 <500ms and >99% committed checks. Use a dedicated fixture, RATE_LIMIT_PER_MINUTE>=10000 and enough source funds/daily limit. Run `TOKEN=... BASE_URL=... k6 run scripts/load.js`; results are saved as load-results.json. The dependency-free `scripts/load.py` runs the same 100/s for 60 seconds scenario. The measured local run committed all 6,000 requests with p50 3.938 ms, p95 44.012 ms and maximum 230.956 ms; raw results and environment are in `docs/performance.json`. This loopback result is a reference measurement, not a production latency guarantee; repeat on representative deployment hardware.

401: check token expiry, issuer/audience/kid/signature and scopes. 403/404: verify source ownership/delegation and destination policy. 409: inspect safe code; rejected IDs must not be repurposed. 503: verify readiness, DB connections and lock contention; retry the same ID. Flyway validation failure: restore the original migration and add a new version. Reconciliation issues: follow `docs/operations.md`. Never reset production balances or disable immutability triggers to clear an incident.
