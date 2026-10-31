# Payment service

Java 21 / Maven / Quarkus 3.15.3 synchronous payment backend. PostgreSQL is the authority for balances, idempotency, immutable payment outcomes and append-only signed ledger entries. Every receipt is returned after commit. No in-memory financial state is used.

## Build and verify

Install Java 21, Maven 3.9+ and Docker. Then, from this folder:

```sh
mvn -B verify
```

Tests require Docker and start disposable PostgreSQL 16 databases. `mvn verify` additionally launches the packaged application in separate processes, verifies restart durability, and performs 300 unique transfers at 100 requests/second with a p95 <500 ms assertion. They do not skip if PostgreSQL is unavailable. The Maven test configuration specifies Docker API 1.44 for compatibility with newer Docker daemons; override `-Dapi.version=...` if needed. Java 17 cannot compile this project.

## Run locally

```sh
docker compose up -d --wait
TOKEN=$(python3 scripts/dev-token.py)
export DATABASE_PASSWORD=local-app-only
export QUARKUS_FLYWAY_USERNAME=payments_owner
export QUARKUS_FLYWAY_PASSWORD=local-owner-only
export JWT_ISSUER=https://local.payment.test
export JWT_PUBLIC_KEY_LOCATION="$(pwd)/.local/public.pem"
mvn quarkus:dev
```

The development profile permits HTTP on localhost:8080 and adds `db/dev` seed data. Alice owns `ACC-1001` (500.000 JOD); Bob owns `ACC-2002` (100.000 JOD). Seeds never reset existing balances. Credentials above are local Docker fixtures, never production values. `scripts/dev-token.py` generates fresh untracked RSA keys and a ten-minute token; it does not use the test keys. Production has no development accounts, authentication bypass or default verification key.

```sh
PAYMENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice 2048\",\"notes\":\"October services\"}"
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/payments/$PAYMENT_ID"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/accounts/ACC-1001/balance
```

Equivalent XML (use a new ID for a second logical transfer):

```sh
curl -i http://localhost:8080/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice 2048</purpose><notes>October services</notes></payment>"
```

Purpose and notes are trimmed. Null/absent/empty notes normalize to empty text. Amounts normalize to exactly three decimal places; more than three input fractional digits are rejected even when zero. Plain decimal syntax is required (no exponent, whitespace or plus sign). Account IDs use 1–128 ASCII letters, digits, hyphens or underscores and are never trimmed. UUID hexadecimal case is canonicalized; source/destination identities retain case. Dates use years 0001–9999 and normalize to instants. Equivalent JSON/XML payloads share a length-prefixed SHA-256 fingerprint. Invalid Unicode surrogate sequences, duplicates, unknown fields, XML namespaces/attributes, nested fields, processing instructions, DTDs and entities are rejected.

Repeated same ID and canonical payload returns the original completed receipt with 200; different content returns 409. Rejected business outcomes are also stable on replay. Existing outcomes replay even after the timestamp window expires. A transient failure returns 503 and never becomes a durable rejection: query status or resend the identical ID. Do not generate a new ID for an uncertain transfer.

## Authentication and authorization

Use an OAuth2/OIDC issuer that issues RS256 access JWTs. Set issuer, audience and HTTPS JWKS URL (or mounted public PEM). Signature, issuer, audience, subject, expiration and issued-at are verified. Tokens have a maximum age of 900 seconds. Required scopes are `payments:write`, `payments:read`, `balances:read`, and `payments:admin` for reconciliation. The JWT `sub` maps to customer `id`, or an explicit `account_debit_authorizations` row. Destination `can_receive` and active state are persisted account policies. Source `transfer_limit` further constrains the global maximum.

Payment lookup/replay belongs to the original initiating principal. Balance lookup requires ownership or explicit debit permission. Missing/unrelated balances and payments return identical 404 responses; unauthorized sources return generic 403 and are not recorded. An unknown or prohibited destination is durably rejected with generic FORBIDDEN to avoid disclosing account existence.

## Configuration

| Environment variable | Default / meaning |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payments` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `payments_app` / no default secret |
| `QUARKUS_FLYWAY_USERNAME` / `QUARKUS_FLYWAY_PASSWORD` | Separate migration owner credentials |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Required issuer; audience `payment-service` |
| `JWT_PUBLIC_KEY_LOCATION` | Required PEM path or HTTPS JWKS location |
| `TLS_CERT_FILE`, `TLS_KEY_FILE` | Required production TLS PEM files |
| `PAYMENT_MAX_AMOUNT` | `10000.000` JOD |
| `PAYMENT_MAX_AGE_SECONDS`, `PAYMENT_MAX_FUTURE_SECONDS` | 2592000 / 300 |
| `PAYMENT_BODY_LIMIT` | 65536; early Content-Length checks and bounded streaming reads enforce it |
| `PAYMENT_RATE_LIMIT_PER_MINUTE` | 6000 requests per authenticated principal, shared across replicas |
| `PAYMENT_RETENTION_YEARS` | 7; minimum operational archive policy, no automatic deletion |
| `OTEL_ENDPOINT` | `http://localhost:4317` |

All Quarkus configuration keys also accept standard uppercase underscore environment variables. Production disables insecure HTTP; provide TLS certificates or explicitly configure a protected TLS-terminating deployment. Proxy forwarded headers are not trusted by default. `PAYMENTS_TRUST_CORRELATION_HEADER=true` may be enabled only behind a trusted gateway that strips untrusted headers; otherwise correlation UUIDs are generated. Responses always return `X-Correlation-ID`.

## Migrations and deployment

Flyway migrations execute at startup and fail closed. Use separate schema-owner credentials for migration and `payments_app` for runtime. V3 grants restricted runtime permissions when that role exists. PostgreSQL transactions use bounded 2-second row-lock and 5-second statement timeouts, acquisition/connect timeouts, and at most three attempts for deadlocks/serialization/lock errors. No automatic retry ever changes payment identity.

```sh
mvn -B verify
docker build -t payment-service:1.0 .
```

Mount secrets and TLS files read-only and configure environment variables above. Run stateless replicas against one PostgreSQL primary. Route `/q/health/live`, `/q/health/ready`, `/q/metrics` only through private operations networking; expose `/api/v1` publicly over TLS. `/q/openapi` serves the complete contract with examples. Financial tables are never deleted through ordinary application flows.

## Performance and troubleshooting

The k6 script measures 100 new transfers/second and asserts p95 <500 ms and <1% HTTP failures. Use a fresh load account with enough opening balance (60 seconds at .001 JOD consumes 6 JOD), a token valid for the run, and a dedicated representative environment:

```sh
export TOKEN=$(python3 scripts/dev-token.py)
k6 run -e BASE_URL=http://localhost:8080 -e TOKEN="$TOKEN" scripts/load.js
```

The performance target is an engineering acceptance target, not a production guarantee. Record hardware, connection pool, DB settings, concurrency and script output before release.

401: check issuer/audience/signature/expiration and clock synchronization. 403: check scopes and persisted principal/account grants. 503: query the payment ID before retrying; inspect DB readiness, pool saturation and lock-timeout metrics. Flyway mismatch: stop deployment and inspect migration history; never edit an applied migration. Docker tests: verify `docker info` and Docker API compatibility. Seed missing: use dev profile on a dedicated development database.

An optional local least-privilege smoke command is `python3 scripts/local-smoke.py` after building and starting Compose; it starts the packaged service on port 18008, applies the explicit development seed fixture, submits 0.001 JOD and exits.

See [operations](docs/OPERATIONS.md) and [verification coverage](docs/VERIFICATION.md).
