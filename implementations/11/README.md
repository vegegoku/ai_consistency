# Payment Processing Backend

Java 21, Maven, Spring Boot 3.4, PostgreSQL 16. The framework is a deliberate alternative to the recommended Quarkus stack; financial persistence uses explicit JDBC transactions. All amounts are JOD with three decimals. No external payment provider is involved.

## Build and verification

```sh
mvn -B verify
```

Requires Java 21, Maven 3.9+, and a running Docker daemon. Tests start an isolated PostgreSQL Testcontainer, apply Flyway migrations, and exercise JSON/XML, JWT signatures/issuer/audience/expiry, ownership and scopes, concurrent debits/duplicate IDs, rollback after debit, durable rejection, lock-timeout retries, restart recovery, immutable ledger and reconciliation. Docker is required; database tests fail rather than silently skip. JAR: `target/payment-service-1.0.0.jar`.

## Local setup

```sh
docker compose up --build -d
```

The local profile alone seeds Alice (`ACC-1001`, 500.000 JOD) and Bob (`ACC-2002`, 100.000 JOD). Local Keycloak is on http://localhost:8081; app is http://localhost:8080. Its demo users `alice` / `bob` both use `local-demo-only`. Demo OAuth client `payment-demo` uses the password grant solely for local testing. The production profile uses no passwords or seeds. Wait until Keycloak's discovery document is available. Token request:

```sh
TOKEN=$(curl --fail -s http://localhost:8081/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payment-demo -d username=alice -d password=local-demo-only \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')
ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"paymentId\":\"$ID\",\"fromAccount\":\"ACC-1001\",\"toAccount\":\"ACC-2002\",\"amount\":\"125.750\",\"date\":\"$DATE\",\"purpose\":\"Invoice\"}"
curl -s http://localhost:8080/api/v1/payments/$ID -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
# The same ID and equivalent XML are a replay (200), not a second debit:
curl -i http://localhost:8080/api/v1/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d "<payment><paymentId>$ID</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.75</amount><date>$DATE</date><purpose>Invoice</purpose></payment>"
```

`201` indicates first committed completion. Same canonical payload/ID returns original receipt with `200`; conflicting fields return `409 IDEMPOTENCY_CONFLICT`. Durable business rejections retain their original error status/body. GET returns `200` for either stored COMPLETED or REJECTED outcome. On a timeout or `503`, reuse the ID or query GET; never generate a new ID to retry an uncertain transfer. Unknown or unauthorized payment/balance queries return identical `404` errors. Nonexistent/unauthorized source or destination transfers return a generic `403`, intentionally concealing existence; `ACCOUNT_NOT_FOUND` is not disclosed in this customer-facing API.

OpenAPI (all responses and JSON/XML examples): [src/main/resources/static/openapi.json](src/main/resources/static/openapi.json). Also `GET /api/v1/openapi` with read scope.

## Configuration and authorization

Environment: `DB_URL`, `DB_USER`, `DB_PASSWORD`; optional `MIGRATION_USER`, `MIGRATION_PASSWORD`, `MIGRATIONS_ENABLED` for a privileged migration identity or external migration job. `OIDC_ISSUER`, `OIDC_JWKS`, `OIDC_AUDIENCE` set trusted issuer, JWKS and required audience. RS256 signatures, timestamps and nonempty subjects are validated. Subject equals `customers.id`; `account_permissions` explicitly grants delegates debit/view access. Submit requires `payments:write`; queries require `payments:read`; reconciliation/metrics require `payments:admin`. Receiving is allowed only when the destination permits incoming payments and both account/customer states are ACTIVE; per-account `transfer_limit` restricts outgoing and incoming amounts. Accounts are provisioned by controlled database operations, not an unaudited public endpoint.

`REQUIRE_TLS` defaults true: configure native TLS with standard Spring `SERVER_SSL_*` variables in production, or place a TLS terminator on a private trusted network and configure authenticated forwarding only there. Do not accept arbitrary client forwarded headers. Plain HTTP is enabled only by explicit local profile/configuration. `TRUST_CORRELATION_HEADER` defaults false; trust headers only from a gateway that replaces client input. Responses always carry generated/validated `X-Correlation-ID`.

`MAX_BODY_BYTES`=65536; `MAX_PAYMENT_AMOUNT`=10000.000; `MAX_FUTURE_SECONDS`=300; `MAX_AGE_SECONDS`=2592000; `RATE_PER_MINUTE`=120 per principal per process; `DB_POOL_SIZE`=20; `AUDIT_RETENTION_DAYS`=2555. A cluster must enforce its aggregate principal rate at the gateway. The bounded in-process limiter is a second layer, not a distributed quota. Account IDs and UUID text are never trimmed. Only purpose/notes are trimmed, absent/null/empty notes canonicalize to empty string, amounts normalize to three decimals, and offsets normalize to instants. Old instruction timestamps are checked for new payments; an already recorded instruction remains replayable after its original date window closes.

Flyway runs versioned migrations on startup. Production runs only `db/migration`; local profile additionally enables `db/local` demo data. Never enable local profile on production. UTC-aware PostgreSQL `timestamptz` and Java Instants store all timestamps. Invalid-account rejected payment identifiers deliberately have no account foreign keys; ledger accounts/payment links have restrictive foreign keys, and deferred database constraints enforce completed debit/credit identity and balance. Ledger/payment records cannot be updated/deleted after finalization. Opening balances establish reconciliation baselines.

## Operation and performance

Health: `/actuator/health/liveness` and `/actuator/health/readiness`; readiness includes PostgreSQL connectivity. Prometheus and metrics require admin scope. Structured logs include correlation ID, valid payment ID, HTTP outcome, error and latency without tokens, account details or payloads. Request tracing uses W3C propagation and OpenTelemetry OTLP (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `TRACING_SAMPLE_RATE`). Receipt/rejection metrics, replay counts, retries and lock failures are provided. Telemetry failure cannot roll back a committed transfer.

For a fresh local fixture, raise `RATE_PER_MINUTE=10000` in the application environment, obtain a token, then:

```sh
TOKEN="$TOKEN" RATE=100 DURATION=10 python3 load/run.py
# Alternatively use k6 for a 30-second run:
TOKEN="$TOKEN" k6 run load/payments.js
```

The load tools fail for errors or p95 >=500ms and emit measured results. Performance is hardware/deployment dependent, not a service guarantee. Small transfers leave adequate seeded funds; repeat load runs require balance reconciliation/top-up by controlled opening-baseline provisioning. See [docs/operations.md](docs/operations.md) for reconciliation, retention, backup/restore, least privilege and incidents; [docs/verification.md](docs/verification.md) for evidence and limitations.

Troubleshooting: a refused Docker connection fails integration tests; start Docker. `401` means issuer/audience/signature/expiry must match (do not log tokens). `403` means scope/account authority/TLS policy failed. `503` lock timeouts are bounded and safe to retry with the same ID. If PostgreSQL migration checksums differ, create a new migration; never edit already-applied financial migrations. Local ports can be overridden with APP_PORT/POSTGRES_PORT/OIDC_PORT; also update Keycloak hostname/issuer if its external address changes. `docker compose down` preserves the database; `down -v` destroys local data.

The demo realm explicitly installs the subject mapper described in [Keycloak protocol-mapper documentation](https://www.keycloak.org/admin-api/protocol-mappers), so access tokens carry the authenticated user ID in `sub`.
