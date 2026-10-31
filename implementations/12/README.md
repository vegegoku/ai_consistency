# JOD payment service

Java 21 / Maven / Quarkus backend implementing the supplied payment specification. PostgreSQL is the source of truth; no balance or idempotency state is held in process memory. API, application, domain, persistence, security, and observability packages have separate responsibilities.

## Build and test

Requires Java 21, Maven 3.9+, and Docker reachable by the current user:

```sh
mvn -B verify
```

The suite starts isolated PostgreSQL 16 containers and restarts the packaged application in the integration-test phase, runs Flyway, validates JWT authentication, exercises REST JSON/XML, concurrent duplicates and overdrafts, rollback after debit, retry after discarded response, lock timeout, immutable data, and reconciliation. Test signing keys live only in `src/test/resources` and never enter the application jar. The Surefire Docker API version is 1.44 to support recent Docker daemons with a minimum API floor; override with `-Dapi.version=...` if necessary. CI runs the same command. Use `mvn -DskipTests package` only to create an image after CI verification.

## Local development

Local credentials and keys below are exclusively for development. Production has no embedded signing key or seed data.

```sh
docker compose up -d --wait
export DB_PASSWORD=local-development-only
export JWT_ISSUER=https://local.payment.test
export JWT_AUDIENCE=payments-api
export PAYMENT_TOKEN=$(python3 scripts/create-dev-token.py alice)
export JWT_PUBLIC_KEY="file:$(pwd)/dev/publicKey.pem"
mvn quarkus:dev
```

In another terminal, after the service starts and Flyway creates the schema:

```sh
docker compose exec -T postgres psql -U payments -d payments < scripts/dev-seed.sql
export PAYMENT_TOKEN=$(python3 scripts/create-dev-token.py alice)
PAYMENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i -H "Authorization: Bearer $PAYMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October services\"}" \
  http://localhost:8080/api/v1/payments
curl -H "Authorization: Bearer $PAYMENT_TOKEN" http://localhost:8080/api/v1/payments/$PAYMENT_ID
curl -H "Authorization: Bearer $PAYMENT_TOKEN" http://localhost:8080/api/v1/accounts/ACC-1001/balance
# Equivalent XML replay returns 200, preserving the original receipt and balances:
curl -i -H "Authorization: Bearer $PAYMENT_TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose><notes>October services</notes></payment>" \
  http://localhost:8080/api/v1/payments
```

The first request yields 374.250 and 225.750 from seeded 500.000 and 100.000 balances. Changing amount or another canonical field while reusing the ID returns 409. GET of a stored rejection returns 200 with its REJECTED error body; POST replays preserve the original business HTTP status/code.

## Configuration

| Environment | Default / meaning |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/payments` with bounded connect/socket and statement timeouts |
| `DB_USER`, `DB_PASSWORD` | `payments`; password required |
| `JWT_PUBLIC_KEY` | Required trusted public PEM/JWKS URL or file location |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Issuer required; audience `payments-api` |
| `PAYMENT_MAXIMUM` | `10000.000` JOD |
| `PAYMENT_FUTURE_SECONDS`, `PAYMENT_AGE_SECONDS` | `300`, `2592000` |
| `PAYMENT_BODY_BYTES` | `65536`; the HTTP and parser limits share this setting |
| `PAYMENT_RATE_PER_MINUTE` | `600`, fixed minute windows shared across replicas in PostgreSQL |
| `PAYMENT_LOCK_MS`, `PAYMENT_STATEMENT_MS`, `PAYMENT_RETRIES` | `2000`, `5000`, `2` extra attempts, same payment ID |
| `PAYMENT_REQUIRE_TLS` | `true`; dev/test profiles disable it |
| `AUDIT_RETENTION_DAYS` | `2555`; minimum configured archival age, see operations |
| `OTEL_ENDPOINT` | `http://localhost:4317` OTLP tracing |

JWTs require signed RS256, subject, issuer, audience, issue time, and expiry. Scopes are space-separated `scope`: `payments:write`, `payments:read`, `accounts:read`, and privileged `operations:read`. Subject maps to `customers.id`; delegated debit/view grants are in `account_permissions`. Destination accounts must be active and receive-enabled, with both account limits satisfied. Missing source/destination or unauthorized debit yields a generic FORBIDDEN rejection to prevent account enumeration. Owners and delegates may see authorized accounts and payment participants; unrelated principals receive 404.

No floating point arithmetic is used for incoming money or balances. Amounts are decimal number/string with plain notation, positive, no more than three lexical fractional digits, and no implicit rounding. Account IDs and UUID input cannot have incidental whitespace. Purpose and notes are stripped; absent/null notes canonicalize to empty text. ISO offsets canonicalize to an instant, amounts to three places, and UUIDs to their UUID value. XML permits only an unnamespaced `payment` root with scalar known elements, no attributes, DTDs, entities, processing instructions, or nested elements. JSON unknown keys, duplicate keys, non-scalar values, and trailing documents are rejected.

## Persistence and deployment

Flyway runs versioned migrations from `src/main/resources/db/migration` at startup. For separated migration credentials use `QUARKUS_FLYWAY_USERNAME`/`QUARKUS_FLYWAY_PASSWORD` and give the runtime account only the grants in [operations](docs/operations.md). Migration credentials belong in a secret store. Schema changes require a new versioned migration; never edit an applied migration.

```sh
docker build -t payment-service:1.0 .
```

The application listener uses HTTP/1.1; terminate HTTP/2 at the trusted ingress. This avoids a Quarkus 3.15 HTTP/2 incompatibility between early bounded body buffering and REST request initialization. Production disables plain HTTP by default. Mount a TLS key/certificate and configure `QUARKUS_HTTP_SSL_CERTIFICATE_FILES` and `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES` (or keystore properties), and expose port 8443. TLS at a trusted ingress may use HTTP to the service only on a private restricted network with explicit `QUARKUS_HTTP_INSECURE_REQUESTS=enabled`, `PAYMENT_REQUIRE_TLS=false`; do not expose that listener publicly. Forwarded headers are not trusted by default. Enable `payment.trust-correlation-header=true` only behind an ingress that overwrites incoming correlation headers; otherwise IDs are generated server-side. Health, metrics, and OpenAPI are on `/q/health/live`, `/q/health/ready`, `/q/metrics`, `/q/openapi`; restrict `/q` at ingress to monitoring/operations networks.

## Operations and verification limits

See [operations](docs/operations.md), [verification evidence](docs/verification.md), and [OpenAPI](src/main/resources/META-INF/openapi.json). Static OpenAPI is also served by Quarkus. HTTP response bodies default to JSON; `Accept: application/xml` selects XML; unsupported request/response media return 415/406. All REST responses include `X-Correlation-ID`, no-store, and nosniff headers. Logs include correlation/payment IDs, outcome and latency without payloads, tokens, account identifiers, or balances.

Run a 100 requests/second reference load after provisioning sufficient funds and raising the configured per-principal rate to at least 6000/minute:

```sh
PAYMENT_TOKEN="$PAYMENT_TOKEN" python3 scripts/load-test.py --rate 100 --seconds 60
```

The script records p95, throughput, HTTP statuses and pass/fail in JSON; 500 ms is an environment-dependent engineering target, not a guarantee. Record CPU/RAM, replica count, database settings, and network placement alongside results.

Troubleshooting: missing configuration errors require environment secrets above; JWT 401 requires matching key/issuer/audience/expiry; 403 requires scope and a database ownership/debit grant; database 503 requires checking readiness, connection limits, and lock contention. Never retry uncertain transfers using a new UUID. A duplicate-ID 409 requires retrieving the original result and submitting a genuinely corrected logical payment with a new UUID.

For an isolated reproducible reference run after `mvn verify`, run `python3 scripts/reference-benchmark.py`. It creates a disposable PostgreSQL container, launches the packaged service with local-only keys and an increased rate limit, seeds accounts, runs 60 seconds at 100 requests/second, records accounting totals, and removes its service/container afterward. It writes `docs/load-results.json` and `docs/load-reconciliation.txt`.
