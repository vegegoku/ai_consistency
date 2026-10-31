# Payment processing service

Java 21, Maven, Spring Boot 3.4, PostgreSQL 16. Spring Boot is the framework choice in place of the specification's recommended Quarkus; explicit JDBC keeps financial row locking visible. JSON and XML pass through one strict parser and immutable command and the same transfer service.

## Build and verify

Install Java 21, Maven 3.9+, Docker with access to its socket. Run `mvn verify`. Tests start disposable PostgreSQL containers and fail if Docker is unavailable; there is no H2 substitution or automatic skipping. The Docker client API is explicitly set to 1.44 for modern daemon compatibility. CI runs the same command. `mvn package -DskipTests` builds only and is not release verification.

Tests cover parser abuse, field aggregation, authenticated API calls, XML/JSON canonical replay, unauthorized access, missing scopes, JWT signature/issuer/audience/expiry, amount precision, durable rejections, unique-ID races, simultaneous source spending, injected SQL failure after debit, ledger immutability, reconciliation corruption, body limits, and health readiness.

## Local setup

1. `docker compose up -d` starts local PostgreSQL and Keycloak. All supplied passwords are explicitly development-only and services bind loopback.
2. Start the app: `DB_PASSWORD=local-development-only REQUIRE_TLS=false mvn spring-boot:run`. Flyway applies `src/main/resources/db/migration/V1__payment_schema.sql` automatically before readiness.
3. In another terminal: `docker compose exec -T postgres psql -U payments -d payments < dev/seed.sql`.
4. Obtain a local token (requires curl and jq):

```sh
TOKEN=$(curl -fsS http://localhost:8081/realms/payments/protocol/openid-connect/token \
 -d grant_type=password -d client_id=payment-service -d username=alice -d password=alice-dev-only | jq -r .access_token)
PAYMENT_ID=$(cat /proc/sys/kernel/random/uuid)
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments \
 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
 -d "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\"}"
curl -fsS "http://localhost:8080/api/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $TOKEN"
curl -fsS http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

For XML, use a new payment ID, `Content-Type: application/xml`, and optionally `Accept: application/xml`:

```xml
<payment><paymentId>REPLACE-WITH-UUID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.000</amount><date>REPLACE-WITH-CURRENT-OFFSET-TIMESTAMP</date><purpose>Invoice</purpose><notes/></payment>
```

The first successful result is 201 and includes Location. A replay is 200, preserving the original processedAt. Reusing an ID with different canonical content is 409. Rejected business outcomes are recorded and replayed with their original code and HTTP status. Querying a recorded rejection returns 200 with status REJECTED. A caller can view payments it initiated and account balances it owns or has an explicit view grant for. Invalid/nonexistent destination accounts return the generic FORBIDDEN to avoid enumeration. No public provisioning/admin mutation APIs exist.

## Configuration

| Environment | Default | Meaning |
|---|---|---|
| DB_URL | jdbc:postgresql://localhost:5432/payments | Persistent PostgreSQL database |
| DB_USER / DB_PASSWORD | payments / empty | Inject credentials via secret manager |
| DB_POOL_SIZE | 20 | Connections per replica |
| JWT_ISSUER | http://localhost:8081/realms/payments | Exact trusted issuer |
| JWT_JWKS_URI | issuer local certs endpoint | Trusted RS256 public key set; HTTPS in production |
| JWT_AUDIENCE | payment-service | Required audience |
| REQUIRE_TLS | true | Reject HTTP unless explicitly disabled locally |
| PAYMENT_MAXIMUM | 10000.000 | Per-message maximum JOD |
| PAYMENT_MAX_AGE / PAYMENT_FUTURE_SKEW | P30D / PT5M | Timestamp windows |
| PAYMENT_MAX_BODY | 65536 | Maximum raw body bytes |
| PAYMENT_RATE | 150 | Requests per authenticated principal per second per replica |
| TRUST_CORRELATION_HEADER | false | Accept only validated UUID header from a trusted gateway |
| TRACE_SAMPLING | 0.1 | OpenTelemetry sampling fraction |
| OTEL_EXPORTER_OTLP_TRACES_ENDPOINT | http://localhost:4318/v1/traces | Collector endpoint |

Amounts never use floating-point arithmetic. Decimal strings and JSON decimal numbers are accepted; exponent notation, nondecimal lexical values, more than 3 fractional digits, and more than 19 significant digits are rejected. UUIDs must use canonical lowercase hyphenated form. Account identifiers are exact, at most 100 characters, with no leading/trailing whitespace. Purpose and notes are stripped at the edges; absent/null notes and empty notes normalize to the same empty text. Dates normalize to instants; equivalent offsets and equivalent amounts generate identical length-prefixed SHA-256 fingerprints. Unknown properties, duplicate fields, nested values, XML attributes/namespaces, DTDs/entities, XInclude and non-UTF-8 declared HTTP charsets are rejected.

Use OAuth scopes `payments:write` for submission and `payments:read` for lookups. `payments:admin` protects reconciliation and metrics. The authenticated JWT subject identifies the customer, or an explicit `account_permissions` grant authorizes a delegate. JWT issuer, audience, expiration, subject, signature and algorithm are enforced. The development Keycloak subject mapper associates users with seeded customers; production identity mappings must come from the trusted issuer, never client-supplied fields.

## Transaction guarantees

A transaction inserts PROCESSING under a database primary key using ON CONFLICT. Concurrent identical requests wait for the first transaction and then retrieve its committed result; lock waits are bounded at two seconds. Accounts are locked in ascending identifier order. Business validation, exact balance changes, two signed ledger rows and final status occur atomically. Deferred constraint triggers forbid committing PROCESSING or unbalanced/mislinked ledgers; final payment rows and ledger records are immutable. Deadlock/lock conflicts retry at most three times, always with the same payment ID. PostgreSQL data survives application and container restarts using the named volume. Non-business database failures roll back and surface 503; never treat an infrastructure error as a durable business rejection. If a response is lost, query or retry the original ID.

See [OpenAPI](docs/openapi.yaml), [operations](docs/operations.md), and [deployment grants](docs/runtime-grants.sql). Build the nonroot container with `docker build -t payment-service .`. Run migrations as a separate owner identity, then run the app with least-privilege grants and `SPRING_FLYWAY_ENABLED=false` in production.

## Troubleshooting

- Build says Java release 21 is unsupported: set JAVA_HOME and PATH to Java 21.
- Docker unavailable: verify `docker info` and socket permissions; tests intentionally fail rather than skip database checks.
- 401: verify token issuer matches JWT_ISSUER, its audience contains payment-service, expiry and clock, and JWKS is reachable.
- 403 TLS_REQUIRED: terminate TLS directly with Spring `SERVER_SSL_*` settings; local HTTP requires explicit REQUIRE_TLS=false. Forwarded headers are not trusted by default.
- 403 FORBIDDEN: confirm authenticated subject ownership/debit grants and active destination restrictions. Do not troubleshoot by opening account visibility.
- 503: inspect database connectivity, lock contention, pool pressure and reconciliation before retrying the same ID; never create a replacement ID for uncertain outcomes.

## Verified reference results

`mvn verify` exercises PostgreSQL integration and security tests with no skipped tests. A separate real HTTP/JWT run achieved 100.00 payments/second for 60 seconds, p95 23.74 ms and zero errors, with 6,100 balanced completed transfers including warmup. Machine-readable measurement is in [performance-result.json](docs/performance-result.json); reproduce with `python3 scripts/measure_local.py` after building (Python `cryptography` required). Production-specific TLS/network/capacity validation remains an operator responsibility.

Payment account identifiers are deliberately stored as historical text without foreign keys because a durable rejection may refer to an unknown account. Ledger entries always reference existing accounts and payments with ON DELETE RESTRICT. Customer/account ownership also has an explicit restrictive foreign key. Financial application APIs do not delete any records.
