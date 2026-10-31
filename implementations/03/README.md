# Payment service 03

Java 21 / Maven / Quarkus backend for exact internal JOD transfers. The JSON and hardened XML adapters share one canonical immutable command. PostgreSQL is the authority for idempotency, deterministic row locks, balances, and immutable double-entry ledger records.

## Build and local run

Prerequisites: Java 21, Maven 3.9+, Docker with permission to access the socket. Run `mvn -B verify` to execute parser, PostgreSQL accounting/concurrency/failure-injection, and authenticated HTTP integration tests, then package the service. Testcontainers provisions isolated PostgreSQL 16 databases; tests do not silently skip when Docker is unavailable. `src/test/resources/docker-java.properties` selects API 1.44 for Docker 29 compatibility.

```sh
docker compose up -d
python3 scripts/dev-token.py > dev/token.txt
export JWT_PUBLIC_KEY="file:$(pwd)/dev/publicKey.pem"
export REQUIRE_HTTPS=false # local loopback development only
mvn quarkus:dev
```

After Flyway has created the schema, seed examples in a second terminal:

```sh
docker compose exec -T postgres psql -U payments payments < dev/seed.sql
export TOKEN="$(cat dev/token.txt)"
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"paymentId\":\"$(uuidgen)\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October services\"}"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/accounts/ACC-1001/balance
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/payments/PAYMENT_UUID
```

XML uses the same path:

```sh
curl -i http://localhost:8080/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
 -d "<payment><paymentId>$(uuidgen)</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.000</amount><date>$(date -u +%Y-%m-%dT%H:%M:%SZ)</date><purpose>Invoice</purpose></payment>"
```

Repeat the exact payment ID and canonical instruction to receive the original receipt with 200; changing its instruction returns 409. Date offsets canonicalize to UTC, amount canonicalizes to three places, and purpose/notes trim surrounding whitespace. Account identifiers and payment IDs are never trimmed. UUID hex case is accepted. Absent notes and empty notes remain distinct. Unknown fields, duplicate keys/elements, nested XML/attributes/namespaces, non-string identifiers, exponent notation and excess fractional digits are rejected. XML permits only the flat contract and prohibits DTD, external entities, XInclude, attributes, namespaces and nested content. Request body limit defaults to 64 KiB. JSON decimals retain lexical precision.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| DB_URL | jdbc:postgresql://localhost:5432/payments | PostgreSQL JDBC URL |
| DB_USER / DB_PASSWORD | payments / local-only-password | Local development connection; override in deployment |
| JWT_PUBLIC_KEY | publicKey.pem | Mounted RSA public key or trusted JWKS URL |
| JWT_ISSUER / JWT_AUDIENCE | https://issuer.example.test / payments | Required JWT claims |
| REQUIRE_HTTPS | true | Reject insecure payment/account API requests |
| PAYMENT_MAXIMUM | 10000 | Per-instruction JOD maximum |
| MAX_BODY_BYTES | 65536 | Parser body byte limit; also set QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE |
| MAX_FUTURE_SECONDS | 300 | Future instruction tolerance |
| MAX_AGE_SECONDS | 2592000 | Maximum instruction age |
| REQUESTS_PER_MINUTE | 600 | Per-principal quota per replica |
| OTEL_DISABLED | true | Set false and configure OTLP for tracing |

The HTTP listener also limits payloads to 64 KiB and timeouts to 10 seconds. Pool acquisition is bounded at 5 seconds, PostgreSQL lock waits at 2 seconds and statements at 5 seconds; deadlocks/serialization failures have at most three same-ID attempts. Set the JDBC URL `connectTimeout=5&socketTimeout=10` for transport-level bounds in deployment. Secrets belong in your deployment secret manager. Set Quarkus TLS certificate/key properties or use a correctly configured trusted TLS ingress. Private `/q/health/live`, `/q/health/ready`, `/q/metrics`, `/q/openapi` endpoints provide health, database readiness, Prometheus metrics and OpenAPI. Static contract is in `src/main/resources/META-INF/openapi.yaml`.

Flyway runs versioned migrations on startup. Use a dedicated migration role and separate least-privilege runtime role in production. Local seed is idempotent and is never automatically loaded into production. Account provisioning must set opening_balance equal to initial available_balance and separately assign destination receive permissions. No public account-administration endpoint exists.

Container: `docker build -t payments-03 .`; run with environment settings and mounted public key/certificates. The runtime image uses a non-root UID. The service is stateless; PostgreSQL stores receipts and balances across restarts.

## Verification and troubleshooting

`mvn verify` exercises JSON/XML success, validation aggregation, duplicate/DTD rejection, original receipt replay, payload conflict, simultaneous duplicates, concurrent debit exhaustion, rollback after debit, response loss replay, new service instance retrieval, hidden unauthorized lookups, frozen accounts, immutable record protections, reconciliation corruption, JWT expiration/scopes, HTTP media negotiation and XML receipts. Tests run against real PostgreSQL, not an in-memory approximation. CI requires Docker. The development-only RSA key utility never ships credentials in the repository.

For Docker API mismatch verify the test resource `api.version=1.44` is on the test classpath. For a database startup failure check `docker compose logs postgres` and DB_URL. Missing public keys or issuer/audience mismatch reject authentication; generate a dev token and use the matching public key. A 503/timeout requires querying/retrying the same payment ID because commit may already have happened. A definitive business rejection is stored and replayed; corrected instructions need a new ID. Application errors never contain SQL text, customer details, balances or stack traces.

See [operations](docs/operations.md) for reconciliation, backup/restore, retention, incident handling, global quota deployment, and the k6 reference workload. The packaged integration test starts two actual JVM processes against the same database, verifies identical receipts after restart, and executes 1,000 transfers at 100 requests/second. It asserts p95 below 500 ms and writes `target/performance.json`. Repeat longer workloads against deployment hardware; local smoke measurements are not a production guarantee.
