# Payment service 06

Java 21 / Maven / Quarkus 3.15 application with PostgreSQL accounting. Both JSON and XML use the same immutable domain command. No binary floating point is used for money. The service commits before returning success. Payments and ledger data survive application restarts.

## Build and test

Install Java 21, Maven 3.9+, Python 3 (for the packaged-service load probe), and a running Docker daemon. Run:

```sh
mvn verify
```

The integration suite starts an isolated PostgreSQL through Quarkus DevServices/Testcontainers. It does not substitute an in-memory database or silently skip database tests. The project sets Docker API 1.44 for modern Docker daemons. The full verify lifecycle includes a real JVM restart and 500-request load probe; it takes approximately one minute on the reference workstation. `JAVA_HOME` must identify Java 21. Test private keys in `src/test/resources` are public test fixtures only, never deployment keys.

Tests cover JSON/XML, duplicate and unknown properties, strict value typing, DTD/XXE, amount precision, field aggregation, authorization, real RS256 signature/issuer/audience/expiry checks, replay, conflicts, rejected outcomes, competing debits, concurrent duplicate IDs, transactional failure injection, immutable ledger, reconciliation corruption, and recovering a receipt after a lost response.

## Local development

```sh
docker compose up -d
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5436/payments mvn quarkus:dev
```

Development migrations seed Alice (`ACC-1001`, 500.000 JOD), Bob (`ACC-2002`, 100.000 JOD), and a frozen Alice account. These fixtures are excluded from production migration locations. DevServices can create a disposable database if the JDBC URL is omitted. No development authentication bypass exists. Supply an RS256 OAuth/OIDC JWT from a trusted identity provider and configure:

```sh
export JWT_ISSUER=https://identity.example/realms/payments
export JWT_JWKS_URL=https://identity.example/realms/payments/protocol/openid-connect/certs
```

JWT audience must include `payment-api`. The authenticated principal name (the JWT `upn`, falling back to preferred username/subject according to MicroProfile JWT) maps to `customers.id`, or an explicit row in `debit_grants`. Identity administrators must ensure that this claim is immutable and cannot be edited by customers. Scopes: `payments:submit`, `payments:read`, `accounts:read`. `admin` group permits reconciliation, but does not bypass debit ownership. Destination accounts must be active, receive-enabled, and satisfy optional `recipient_restrictions` rows. Payment lookups are restricted to the initiating principal; account inquiries to their owner. Protected unknown account details are collapsed into FORBIDDEN/404.

## Examples

Obtain a valid bearer token for Alice from the configured identity provider. Generate a new ID for every new logical instruction; keep the same ID for retries.

```sh
export TOKEN='your-valid-access-token'
export PAYMENT_ID="$(uuidgen)"
export PAYMENT_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$PAYMENT_DATE\",\"purpose\":\"Invoice INV-2048\"}"
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$PAYMENT_DATE</date><purpose>Invoice INV-2048</purpose></payment>"
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/payments/$PAYMENT_ID"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/accounts/ACC-1001/balance
```

The first success returns 201 and a Location. Equivalent retries return 200 with the original receipt. A stored business rejection replays its original HTTP rejection and body. A changed instruction with the same ID returns 409. Decimal strings and numbers are accepted, with up to three lexical fractional digits; exponent notation is rejected. Canonical amounts have exactly three places. Only purpose and notes are trimmed; absent/null notes normalize to empty. Offset timestamps normalize to UTC. Time-window validation applies to new instructions; existing receipts remain replayable as they age.

## Configuration and deployment

`DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_ISSUER`, `JWT_JWKS_URL`, and `OTEL_ENDPOINT` configure dependencies. `PAYMENT_MAXIMUM` defaults to 10000.000, `PAYMENT_MAX_AGE_SECONDS` to 2592000, `PAYMENT_FUTURE_SECONDS` to 300, `PAYMENT_BODY_LIMIT` to 65536, `PAYMENT_REQUESTS_PER_MINUTE` to 600 per principal. Body limits must also be aligned with `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE`; oversized transport requests may be rejected before application parsing. Limits are shared in PostgreSQL across replicas. Account-specific `transfer_limit` and optional recipient allowlists are enforced under account locks.

Production requires secure transport. Configure Quarkus TLS (`QUARKUS_HTTP_SSL_CERTIFICATE_FILES` and `QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES`) and `QUARKUS_HTTP_INSECURE_REQUESTS=disabled`; otherwise the application rejects HTTP API requests. For TLS termination, configure a trusted proxy and Quarkus forwarded-header handling explicitly, with network controls preventing direct untrusted requests. Never accept arbitrary forwarded headers. Mount secrets or inject environment secrets; do not bake them into the image.

```sh
mvn verify
docker build -t payment-service-06 .
```

Flyway runs versioned schema changes at startup. For least privilege, run the migrations with a deployment role before application rollout, then set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` for the runtime role. See [operations](docs/OPERATIONS.md) for role grants, backup, reconciliation, retention, incidents, and load verification. OpenAPI is at `/openapi`, Swagger UI at `/q/swagger-ui`, health at `/q/health/live` and `/q/health/ready`, Prometheus at `/q/metrics`. Restrict operational endpoints at the deployment network layer. OTel exports traces to the configured collector. Structured logs include correlation ID, valid payment ID, HTTP status, and elapsed time; they exclude payloads and secrets.

## Troubleshooting

A 503 means the result may need to be queried with the original payment ID; never invent a new ID to recover an uncertain payment. Confirm database readiness, pool pressure, and lock holders. Lock waits are bounded at 1.5s; statements at 5s; pool acquisition at 3s; deadlock/serialization/lock retries at two. A failed commit is never recorded as a business rejection. Invalid JWTs return 401, missing scopes return 403. Check issuer, audience, expiry, trusted signing keys, and principal mapping. Never include a token or raw payload in tickets. When Docker is unavailable, start it before `mvn verify`; database tests intentionally fail rather than reporting a partial green build.
