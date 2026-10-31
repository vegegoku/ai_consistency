# Payment processing backend

Java 21 / Maven / Spring Boot / PostgreSQL service implementing the supplied specification. Spring Boot is used instead of the recommended Quarkus framework; the API, security and financial behavior are unchanged.

## Build and verify

Install Java 21, Maven 3.9+, and Docker with permission to run containers:

```sh
mvn -B verify
```

This runs strict codec and security unit tests, real PostgreSQL integration tests, concurrency/rollback/lock-timeout tests, and a packaged-process test that restarts the application, replays a lost receipt, and measures a short 100 requests/second HTTP load. PostgreSQL is supplied by Testcontainers; tests do not skip when Docker is absent. Reports are in `target/surefire-reports`, `target/failsafe-reports`, and `target/load-report.json`. The executable artifact is `target/payment-service-1.0.0.jar`.

On a host whose default Java is older, set `JAVA_HOME` to a Java 21 installation and put its `bin` first on `PATH`.

Fresh verification evidence is recorded in [docs/verification.md](docs/verification.md).

## Run locally

```sh
cp .env.example .env
# These credentials are strictly local development fixtures.
docker compose up --build -d
# Wait for http://localhost:8080/actuator/health/readiness to report UP.
docker compose exec -T postgres psql -U payments -d payments < scripts/seed.sql
```

The local realm explicitly includes Keycloak’s [Subject mapper](https://www.keycloak.org/admin-api/protocol-mappers) so tokens carry the stable customer ID. The Compose stack supplies PostgreSQL, a disposable Keycloak development identity provider, and the service. Ports bind only to localhost. The DB volume survives process/container restarts. `docker compose down -v` destroys local financial data; use only for disposable fixtures.

Get Alice's local token (requires `curl` and `jq`):

```sh
TOKEN=$(curl -fsS http://localhost:9090/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=local-cli -d username=alice -d password=alice-local-only | jq -r .access_token)
PAYMENT_ID=$(cat /proc/sys/kernel/random/uuid)
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October\"}"
curl -fsS "http://localhost:8080/api/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $TOKEN"
curl -fsS http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

Submit XML using a new ID, or replay the same fields exactly (including notes and instant):

```sh
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  --data-binary "<payment><paymentId>$PAYMENT_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>$DATE</date><purpose>Invoice INV-2048</purpose><notes>October</notes></payment>"
```

The first transfer returns 201 and changes 500.000 / 100.000 to 374.250 / 225.750. Replays return 200 and the original receipt. Persisted rejections replay with their original HTTP status and body. GET returns 200 for either stored outcome; unknown or hidden results return 404. The instruction time window is enforced for new instructions. Historical identical retries still return the original stored outcome.

## API and authorization

The full specification, with JSON/XML examples and error responses, is served as `/openapi.yaml` and stored at `src/main/resources/static/openapi.yaml`.

- `POST /api/v1/payments`: `payments:write` scope and source ownership/debit delegation.
- `GET /api/v1/payments/{UUID}`: `payments:read`; initiator or an authorized participant.
- `GET /api/v1/accounts/{ID}/balance`: `payments:read`; ownership or explicit view/debit delegation.
- `GET /api/v1/operations/reconciliation`: `payments:admin` scope.
- `/actuator/health/liveness`, `/actuator/health/readiness`: anonymous, no sensitive detail.
- `/actuator/prometheus`: `payments:admin` scope.

JWT subject is the customer's stable ID, or a principal in `account_authorizations`. Only an operational provisioning process may create customers/accounts, set opening balances, grant delegation, or change account policy. There are no unauthenticated provisioning endpoints. Account absence and unauthorized destinations are intentionally concealed as `403 FORBIDDEN`; unrelated lookups are 404.

JSON amount may be a decimal number or string. Numeric exponents, leading plus signs, leading zeroes, whitespace, non-decimal values and more than three fractional digits are rejected. Account IDs follow `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Identifiers are not trimmed; UUIDs accept the standard hyphenated case-insensitive representation. Purpose and notes are trimmed; omitted notes and empty notes remain distinct. Date is normalized to an instant, amount to three decimal places, and fields are length-framed before SHA-256 fingerprinting. Unknown/duplicate fields, nulls, nested values, XML namespaces/attributes, DTDs, entity references and processing instructions are rejected.

## Configuration

| Environment variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payments` | PostgreSQL JDBC connection; production TLS belongs in URL |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | payments / empty | Runtime credentials; inject from secret manager |
| `JWT_ISSUER` | invalid example issuer | Exact expected `iss` |
| `JWT_JWK_SET_URI` | invalid example JWKS URI | Trusted public key endpoint; RS256 signature validation |
| `JWT_AUDIENCE` | payment-service | Required `aud` |
| `REQUIRE_HTTPS` | true | Reject insecure financial requests |
| `PAYMENT_MAXIMUM` | 10000.000 | Maximum incoming amount |
| `PAYMENT_FUTURE_SECONDS` | 300 | Future instruction tolerance |
| `PAYMENT_AGE_SECONDS` | 2592000 | Maximum instruction age |
| `MAX_BODY_BYTES` | 65536 | Bounded request body |
| `RATE_PER_MINUTE` | 6000 | Per-principal, per-replica fixed window limit |
| `DB_ATTEMPTS` | 3 | Bounded retries with same payment ID |
| `DB_POOL_SIZE` | 20 | Maximum JDBC pool size |
| `TRUST_CORRELATION_HEADER` | false | Accept only trusted ingress correlation IDs when true |
| `TRACE_SAMPLE_RATE` | 0.1 | OpenTelemetry trace sample rate |
| `OTLP_ENDPOINT` | http://localhost:4318/v1/traces | Trace collector |
| `PORT` | 8080 | HTTP listener |

Database lock timeout is 1.5 seconds, SQL timeout 5 seconds, transaction timeout 10 seconds, pool acquisition 3 seconds and retry backoff 20/40 ms. Account policies are persisted `max_single_payment`, `daily_limit` (UTC day), `debit_enabled`, and `receive_enabled`. Scale is exactly three throughout `NUMERIC(19,3)` and `BigDecimal`.

Flyway runs versioned migrations automatically on startup. Never edit a migration after deployment. For production use separate migration credentials via `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD`; provision the runtime role and apply `scripts/runtime-grants.sql`. A migration owner must own the schema and triggers. Local Compose deliberately uses one development DB identity.

## Financial design and operations

`INSERT ... ON CONFLICT` on the database payment primary key safely waits for concurrent winners. Source/destination rows are locked in ascending ID order; authorization, policy limits and funds are checked while locked. Debit, credit, both ledger entries and the final outcome commit together. Rejection is durable; infrastructure errors roll back rather than masquerading as rejection. Completed payments and ledger entries have immutability triggers, and a deferred database constraint verifies exactly two matching balanced entries at commit. Receipt construction occurs inside the transaction but HTTP rendering occurs only after `TransactionTemplate` commits.

The service is stateless and supports multiple replicas sharing PostgreSQL. Rate limits are local to each replica; enforce a global quota at an authenticated gateway when required. Production TLS, operational retention, backups and incident procedures are documented in [docs/operations.md](docs/operations.md). Run the repeatable external reference load with `k6 run scripts/load.js`; provide `BASE_URL` and `TOKEN` environment variables. Review `target/load-report.json` for the automated local smoke measurement; validate sustained capacity on representative hardware before setting service-level objectives.

## Troubleshooting

- 401: verify issuer, audience, signature, expiry, subject and the trusted JWKS URL; never paste tokens into logs.
- 403: check HTTPS, required scopes, stable subject/customer mapping, account delegation and policy restrictions.
- 409: inspect the safe error code. A corrected business instruction requires a new UUID.
- 503: query the original UUID or retry exactly the same payload; never generate a fresh ID automatically.
- Docker test startup failure: Docker must be accessible. Tests set docker-java API version 1.44 for modern daemons; change the system property only when the daemon requires another supported version.
- Failed readiness: inspect database connectivity, migration permissions and schema history. No financial operation executes without a database transaction.
- Occupied local ports: change the host port mappings in Compose, and keep the JWT issuer consistent with the address used to obtain tokens.
