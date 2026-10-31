# Internal JOD payment service

Java 21, Maven, Spring Boot 3, PostgreSQL 16, Flyway, Spring Security OIDC/JWT and explicit JDBC transactions. The supplied requirements are in `payment-processing-requirements.md`; the framework is a technical choice because Quarkus was recommended rather than mandatory.

## Build and test

Install a Java **21 JDK** (a JRE alone is insufficient), Maven 3.9+, and a working Docker daemon, then run:

```sh
mvn -B verify
```

JUnit tests launch isolated real PostgreSQL Testcontainers. Docker is mandatory; integration tests never silently skip. Tests cover exact money movement, JSON/XML validation and canonicalization, JWT signature/issuer/audience/expiry, authorization and concealment, idempotency, concurrent duplicates and competing withdrawals, rollback injected by a PostgreSQL trigger, lost commit acknowledgements and same-ID recovery, immutable records and reconciliation corruption. CI runs the same command. Artifacts and reports appear under `target/`.

## Local development

All credentials below are explicitly disposable local credentials. Production has no seeded accounts unless the `local` profile is deliberately enabled.

```sh
docker compose up --build -d
# Wait for app health and Keycloak realm import:
curl --fail http://localhost:18015/actuator/health/readiness
export TOKEN="$(./scripts/token.sh)"
```

The app uses port 18015; PostgreSQL 15415; Keycloak 18115, all bound to loopback. `alice` / `alice-local-only` and `bob` / `bob-local-only` are local Keycloak users, subjects `dev-alice` and `dev-bob`. Keycloak admin is `admin` / `admin-local-only`. The local client explicitly includes Keycloak’s [subject mapper](https://www.keycloak.org/admin-api/protocol-mappers) so access tokens identify the provisioned account owner. The development client grants administrative scopes for operational testing only; production clients must have narrowly assigned scopes.

Seeded accounts: `ACC-1001` belongs to Alice with `500.000 JOD`; `ACC-2002` belongs to Bob with `100.000 JOD`. Repeatable development seed SQL only inserts missing fixtures, never resets existing balances. Preserve the PostgreSQL volume for restart durability; `docker compose down` retains it.

```sh
PAYMENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl --fail-with-body -i http://localhost:18015/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "{\"paymentId\":\"$PAYMENT_ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":125.750,\"date\":\"$DATE\",\"purpose\":\"Invoice INV-2048\",\"notes\":\"October services\"}"
curl --fail-with-body "http://localhost:18015/api/v1/payments/$PAYMENT_ID" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body http://localhost:18015/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
```

First submission returns `201` after commit with a Location header; the same ID and same canonical instruction returns `200` and its original receipt. Reusing it with altered fields returns `409`. Keep the original date when retrying. Already stored instructions can still replay when the timestamp window or configured maximum has subsequently changed; new instructions must satisfy current admission policy. Durable business rejections replay their original HTTP error and body. `GET` returns a durable rejection with `200` as the status-resource representation. On `503`, timeout or response loss, query or retry with the **same** ID.

XML uses the same command and application service:

```sh
XML_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
curl --fail-with-body -i http://localhost:18015/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  --data "<payment><paymentId>$XML_ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>1.250</amount><date>$DATE</date><purpose>XML transfer</purpose></payment>"
```

Amounts accept ordinary decimal strings or numbers, never exponent syntax, booleans or nested objects. Output always has 3 decimals. UUIDs must use canonical lowercase form. IDs are not trimmed; purpose and notes are trimmed, with absent/null notes equivalent to empty notes. Unknown/duplicate fields, XML namespaces/attributes, DTDs/entities, nested markup and multiple roots/documents are rejected. XML input is parsed with hardened StAX, independently of output serialization. JSON defaults unless Accept selects XML; unsupported Accept is `406`, request media `415`, body overflow `413`.

## Configuration and deployment

| Variable | Default / purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL JDBC URL and runtime secret; local defaults only |
| `MIGRATION_USER`, `MIGRATION_PASSWORD` | Dedicated schema owner; defaults to local DB user |
| `DB_POOL_SIZE` | 20 connections per replica |
| `JWT_ISSUER`, `JWT_JWKS_URI`, `JWT_AUDIENCE` | Exact issuer, signed JWK discovery and `payments-api` audience |
| `REQUIRE_HTTPS` | `true`; local profile/Compose disables for loopback only |
| `MAX_BODY_BYTES` | 65536; streamed reads stop at limit + 1 |
| `MAX_PAYMENT_AMOUNT` | 10000.000 JOD |
| `MAX_FUTURE_SECONDS`, `MAX_AGE_SECONDS` | 300 and 2592000 |
| `RATE_LIMIT_PER_MINUTE` | 600 per authenticated principal per replica |
| `DB_MAX_RETRIES` | 2 additional attempts for transient DB conflicts |
| `FINANCIAL_RETENTION_YEARS` | 7, financial archive policy; no automatic financial deletion |
| `ISSUE_RETENTION_DAYS` | 90, operator retention-job parameter; see runbook |
| `OTEL_EXPORT_ENABLED` | false; set true and configure OTLP endpoint to export traces |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` |
| `TRACING_SAMPLE_PROBABILITY` | 0.1 |

Production must use trusted issuer/JWKS endpoints over HTTPS, secret injection, no `local` profile, separate runtime/migration identities and least-privilege grants from `dev/runtime-grants.sql`. Run Flyway using the migration identity at application startup (with controlled single release deployment) or in a dedicated migration job before rolling replicas; migrations are versioned and checksummed. Do not change applied migration files. Disable startup migration for runtime-only replicas with `SPRING_FLYWAY_ENABLED=false` after the job.

Terminate TLS in the application using `SERVER_SSL_ENABLED=true`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD` and `SERVER_SSL_KEY_STORE_TYPE=PKCS12`, or re-encrypt traffic from the ingress to the application. Public forwarding headers are deliberately ignored, so clear HTTP ingress traffic is rejected when HTTPS is required. Restrict health access with network policy. Database TLS (`sslmode=verify-full`) and CA verification belong in the production JDBC URL.

Required scopes are `payments:write`, `payments:read`, `accounts:read`, and `payments:admin` for their respective routes. An account owner is resolved by the validated JWT subject through `customers.subject`; arbitrary request claims cannot establish ownership. Explicit debit grants reside in `account_debit_authorizations`. Destination receive flags, customer/account status, source transfer enablement, optional destination allow-list, per-transfer and UTC daily limits are checked under row locks. Unknown/unowned sources and missing destinations use generic `FORBIDDEN` to avoid enumeration. Payment lookups allow the original initiator or current source/destination owner; balance lookups require ownership or an explicit grant.

Each database attempt sets a 2s lock and 5s statement timeout; transaction timeout is 10s, connection acquisition 3s. At most 3 attempts use the same ID. Both accounts are locked in sorted order, and customer rows are locked to stabilize customer policy. Unique payment IDs and deferred PostgreSQL ledger guards enforce final outcome integrity. Final payments and ledger entries reject update/delete. Opening balances are immutable. No endpoint provisions or deletes accounts or grants.

## API and operations

The complete OpenAPI 3.0 document (JSON is also valid YAML) is served at `/openapi.yaml` and stored at `src/main/resources/static/openapi.yaml`. It includes both request examples, all error responses, schemas and scope descriptions. Health endpoints are `/actuator/health/liveness` and `/actuator/health/readiness`; readiness includes database availability. Prometheus is at `/actuator/prometheus`, requiring admin scope. Automatic HTTP and tracing instrumentation accompanies custom outcome, replay, processing-duration, retry, lock-timeout and unexpected-failure metrics. Logs are structured JSON with generated correlation IDs; public correlation headers are ignored.

See [OPERATIONS.md](OPERATIONS.md) for reconciliation, retention, backup/restore, incident handling and load measurement.

Troubleshooting: missing Docker causes verification to fail; start the daemon and check socket permissions. Maven release errors mean Maven is using a pre-21 JDK (`mvn -version`). A 401 usually means expired token or mismatched issuer/audience; Keycloak issuer uses the browser-facing port while JWKS can use its internal Compose address. A 403 indicates scope/ownership/destination policy. A 503 requires same-ID retry or query, then inspecting database health and lock pressure. Flyway authentication uses the migration identity separately from datasource credentials.
