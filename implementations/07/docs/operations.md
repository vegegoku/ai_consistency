# Operations runbook

## Deployment and security

Run at least two stateless replicas against one managed PostgreSQL writer. Use Java 21, the nonroot production image, memory/CPU limits, network policy and a secret manager. Provision a dedicated schema owner for Flyway and a restricted `payment_runtime` role using `scripts/runtime-grants.sql`. Account provisioning and authorization changes are privileged audited administrative operations outside the public API.

Default `REQUIRE_HTTPS=true` rejects cleartext financial traffic. Configure native TLS with `SERVER_SSL_ENABLED=true`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD` and `SERVER_SSL_KEY_STORE_TYPE=PKCS12`. If a trusted ingress terminates TLS, explicitly configure Spring's forwarded-header strategy and prevent any path around that ingress; strip all client-supplied forwarding and correlation headers there. Forwarded headers are ignored by default. Connect to PostgreSQL with `sslmode=verify-full` and an authenticated trusted certificate.

Configure an OIDC provider with stable subject IDs, exact issuer and payment-service audience; issue `payments:write`, `payments:read`, or `payments:admin` scopes only as appropriate. RS256 signature, issuer, audience, expiration, not-before and subject claims are checked. The standard JWT clock skew is 60 seconds. Restrict JWKS egress and configure rotation in the identity provider. The local Compose realm/password grant and development credentials must never be deployed to production.

Use readiness for traffic admission and liveness for process checks. Readiness includes an actual DB check. Inspect structured logs and Prometheus metrics: HTTP request status, `payments.outcomes` by code, `payments.replays`, `payments.processing`, DB retries/lock timeouts and unexpected failures. Spring tracing propagates W3C trace context to an OTLP collector. Disable exporting via `MANAGEMENT_TRACING_ENABLED=false` in isolated environments. Logs contain correlation ID, valid payment ID, authenticated principal, outcome code, HTTP status and latency, never bearer tokens/full instructions/balances. Set `TRUST_CORRELATION_HEADER=true` only behind a trusted ingress that strips incoming headers.

## Reconciliation

Run `GET /api/v1/operations/reconciliation` with a `payments:admin` bearer token after deployment, daily, after failover and before/after restoring. Schedule it in your operational scheduler; alert whenever `consistent=false` or the endpoint is unavailable. The report runs in a repeatable-read snapshot and detects missing, extra, unbalanced or misdirected ledger entries, entries attached to rejected payments, opening-balance disagreements and incorrect ledger balance snapshots. It returns identifiers only to privileged callers.

Every account has a fixed `opening_balance` recorded during audited provisioning. Financial updates must go through the transfer service; the opening baseline and owner are immutable. Reconciliation does not automatically repair discrepancies. Pause payment admission, preserve logs and database snapshots, identify the root cause, and create an approved compensating instruction or migration under dual control. Never edit or delete a finalized ledger entry through the runtime identity. Test fixtures deliberately corrupt records only with a privileged database owner to verify detection.

## Backup and restore

Use managed PostgreSQL point-in-time recovery with encrypted continuous WAL archiving, daily encrypted full backups and replica failover. Set deployment-specific `BACKUP_RETENTION_DAYS` (initial recommendation 35 days), RPO and RTO in the backup platform, not application memory. Restrict backup access, rotate keys, and store copies in a separate failure domain.

For a local logical backup: `pg_dump --format=custom --file=payments.dump "$PGDATABASE"`. Supply connection details through the secret manager/libpq environment and never include passwords in shell history. Restore into a new isolated database with `pg_restore --no-owner --dbname="$RESTORE_DATABASE" payments.dump`, provision grants separately, validate Flyway checksums, run reconciliation, verify balances/receipts, and only then cut over. Exercise a restore drill at least quarterly and record achieved RPO/RTO and reconciled totals. The release verification also restarts the actual Java application against existing PostgreSQL state and checks replay correctness.

## Retention and audit

Financial tables are append-only historical records; application flows never purge them. Default financial retention is indefinite until an organization-approved jurisdiction-specific retention policy exists. Configure `FINANCIAL_RETENTION_YEARS`, `AUDIT_LOG_RETENTION_DAYS` and `BACKUP_RETENTION_DAYS` in the organization's archival/log/backup infrastructure and document the selected legal basis and values in the deployment record. They are operational platform settings, not service environment switches. A typical initial operational log setting is 90 days; financial retention must be approved for the applicable jurisdiction. Never treat this example as legal advice or delete records automatically on an unreviewed timer.

Use an immutable log archive with restricted access. Payment records persist the initiating principal, original correlation ID, fingerprint and processing timestamp. Disable or redact SQL/payload debug logging. Archive and verify financial records before any approved retention operation; preserve related ledger, payment and audit records as one unit. Privileged archival/deletion is outside the runtime DB role and must retain idempotency tombstones for the supported retry horizon.

## Incidents and uncertain outcomes

For database loss, lock exhaustion or 503 responses, preserve the original payment ID. Query status after recovery; a 404 after a known rollback permits retrying the same instruction. When a commit response is lost, an identical retry returns the stored receipt. A rejected instruction remains rejected; a corrected amount/account requires a new ID.

For suspected duplication or a reconciliation mismatch, stop admission at the gateway, preserve DB backups/WAL/logs and trace IDs, identify affected UUIDs, and reconcile against customer balances before reopening traffic. Never manually credit a destination merely because a client timed out. Database uniqueness and deterministic row locking are the correctness controls; no distributed lock or background balance updates are used.

## Performance

The engineering target is p95 below 500 ms at 100 requests/second. `mvn verify` includes a short real HTTP/JWT/PostgreSQL smoke load and records the measured p95 in `target/load-report.json`; it does not assert a hardware-independent guarantee. Run `BASE_URL=https://... TOKEN=... k6 run scripts/load.js` for 60 seconds against a dedicated reference environment with at least 10,000.000 JOD available. The load uses unique IDs and 0.001 JOD payments. Record host CPU/RAM, network latency, replica and DB pool sizes, database configuration, throughput, p95, error rate and reconciled totals. Tune pool/replica counts against DB capacity, and retain the report with each release. The k6 thresholds enforce the performance target for that environment.
