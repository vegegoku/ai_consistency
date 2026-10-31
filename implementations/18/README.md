# Payment processing service

Java 21 / Maven / Quarkus 3.15.3 service. PostgreSQL is the only financial datastore. JSON and XML both produce one immutable command and one synchronous transfer transaction.

## Build and tests

Install JDK 21, Maven 3.9+, and Docker, then run `mvn verify`. Testcontainers starts isolated PostgreSQL 16, applies Flyway, and exercises REST security, precision, idempotency, races, rollback, immutable ledger records and reconciliation. Failsafe then launches the packaged application as separate JVM processes to check response loss, forced restart, database outage/recovery, and a 1,000-request reference load at 100 requests/second. Tests use temporary RSA test keys under `src/test/resources`; these are deliberately public test fixtures and are never packaged into the deployed application. Docker API 1.44 is pinned for compatibility with Docker 29. Test HTTP ports are dynamically allocated.

## Local development

1. `docker compose up -d postgres`
2. Set `JWT_ISSUER` to your OAuth/OIDC issuer, `JWT_JWKS_URL` to its RSA signing JWKS URL, and `JWT_AUDIENCE=payment-service`. Tokens must have a stable `sub` and a space-separated `scope` claim. The source account owner is the customer ID matching `sub`, or an explicit `account_grants.can_debit` grant. No development authentication bypass exists.
3. `mvn quarkus:dev` applies Flyway migrations at startup. Production runtime replicas should use a dedicated migration job and `QUARKUS_FLYWAY_MIGRATE_AT_START=false`.
4. `docker compose exec -T postgres psql -U payments -d payments < ops/dev-seed.sql`
5. Obtain an issuer-signed bearer token whose `sub=alice`, `aud=payment-service`, and scope includes `payments:write payments:read`. Set `TOKEN` locally without putting it in source control.

Submit with a new UUID and a current offset timestamp:

```bash
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"paymentId":"d92192df-f79a-4553-8120-5660d2eb1294","fromAccount":"ACC-1001","toAccount":"ACC-2002","amount":"125.750","date":"2026-10-28T13:30:00+03:00","purpose":"Invoice INV-2048","notes":"October services"}'
curl http://localhost:8080/api/v1/payments/d92192df-f79a-4553-8120-5660d2eb1294 -H "Authorization: Bearer $TOKEN"
curl http://localhost:8080/api/v1/accounts/ACC-1001/balance -H "Authorization: Bearer $TOKEN"
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/xml' -H 'Accept: application/xml' \
  -d '<payment><paymentId>d92192df-f79a-4553-8120-5660d2eb1294</paymentId><fromAccount>ACC-1001</fromAccount><toAccount>ACC-2002</toAccount><amount>125.750</amount><date>2026-10-28T10:30:00Z</date><purpose>Invoice INV-2048</purpose><notes>October services</notes></payment>'
```

The illustrative date must be updated if outside the 30-day instruction window. Replays retain their original receipt and return 200; first completions return 201. Business rejections are durable and repeat the original rejection; changed instructions require a new UUID. Amounts are exact decimal strings with three places in receipts. `purpose` and `notes` are stripped of leading/trailing whitespace; absent/null notes canonically equal empty notes. Account IDs and lowercase canonical UUIDs are never silently modified. Account IDs are limited to 128 characters. XML namespaces, attributes, processing instructions and nested fields are forbidden. An HTTP `503` means the outcome must be queried or retried with the same ID.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| DB_URL | jdbc:postgresql://localhost:5432/payments | PostgreSQL JDBC URL |
| DB_USER / DB_PASSWORD | payments / change-me-local-only | Override with managed secrets in deployment |
| JWT_ISSUER / JWT_JWKS_URL / JWT_AUDIENCE | non-resolving example issuer / JWKS / payment-service | Required issuer, trusted keys, audience |
| TLS_CERT / TLS_KEY | unset | Production PEM certificate and private key paths |
| PAYMENT_MAXIMUM | 10000.000 | Message maximum JOD |
| PAYMENT_FUTURE_SECONDS / PAYMENT_OLD_DAYS | 300 / 30 | Timestamp window |
| PAYMENT_BODY_LIMIT | 65536 | Application parser byte cap; also configure QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE consistently |
| PAYMENT_LOCK_TIMEOUT_MS / PAYMENT_STATEMENT_TIMEOUT_MS | 2000 / 5000 | PostgreSQL bounded waits |
| PAYMENT_RETRIES | 2 | Additional retries for known aborted lock/deadlock/serialization transactions |
| PAYMENT_RATE_PER_MINUTE | 6000 | Per-principal, shared PostgreSQL fixed-minute rate limit |
| AUDIT_RETENTION_DAYS | 2555 | Minimum operational retention policy, see operations guide |
| OTEL_EXPORTER_OTLP_ENDPOINT / TRACE_SAMPLE_RATIO | localhost:4317 / 0.1 | Trace export and sampling |

Production HTTP is disabled; configure TLS certificate/key and use port 8443. Dev/test permit HTTP. If TLS terminates at an ingress, use encrypted ingress-to-service connections. Do not enable insecure HTTP for deployed environments. Protect management `/q` endpoints at the ingress/network; `/api/v1/admin/reconciliation` additionally requires `payments:admin`.

## API and operations

OpenAPI is served at `/q/openapi` and the checked-in specification is `src/main/resources/META-INF/openapi.yaml`. Health: `/q/health/live`, `/q/health/ready`; metrics: `/q/metrics`. Only the originating caller can retrieve a payment; balances require ownership or explicit view grants. Unknown and unauthorized lookups both return 404. Missing/unauthorized source accounts return 403; destination details are never leaked in a rejection. A caller also needs a write scope; recipient eligibility is enforced under account locks with `can_receive`, active status and fixed currency. Daily debit policy is per source account in UTC.

See [operations](docs/OPERATIONS.md) for reconciliation, backups, retention and incidents. Run `docker build -t payment-service:18 .` for a nonroot Java 21 container. CI runs `mvn -B verify` using PostgreSQL Testcontainers.

## Troubleshooting

- Docker unavailable: start Docker and verify `docker info`; integration tests intentionally fail rather than silently skip PostgreSQL verification.
- Authentication 401: check signature/JWKS, exact issuer and audience, required exp/iat/sub claims, clock and token lifetime (maximum 1 hour).
- Authorization 403: check write scope, owner/debit grant, recipient eligibility. Receipt/balance 404 may indicate authorization, not absence.
- 409: a final business rejection or payload conflict; inspect the stable error code. Corrected payloads need a new UUID.
- 503: query the original ID, inspect database readiness and lock metrics; never invent a replacement ID for an uncertain result.
- Flyway checksum mismatch: add a new versioned migration; never edit an already applied production migration or run clean.
- Load measurement: run `TOKEN=... k6 run ops/load-test.js` against representative hardware with a funded source and rate limit >=6000. The p95 <500ms at 100/s target is measured by thresholds, not claimed from unit tests.
