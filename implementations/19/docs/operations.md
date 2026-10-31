# Operations runbook

## Deployment and security

Use PostgreSQL 16+ with durable storage, synchronous commit enabled, WAL archiving, monitored replication, encrypted disks and tested point-in-time recovery. Set JVM and DB timezone UTC. Run the SQL migrations as an owner/migrator; runtime identity receives only `runtime-grants.sql`. The local Compose owner account and local Keycloak users are not production identities. Restrict network access to database and JWKS; supply secrets from a secret manager. Rotate credentials with controlled connection pool recycling. TLS is required by default; configure `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`, `SERVER_SSL_KEY_STORE_TYPE` and `SERVER_PORT`, or explicitly document a trusted encrypted ingress architecture. The application does not trust arbitrary forwarded headers.

Enforce a distributed request limit at the ingress across replicas; the application's principal counter is an additional per-replica protection. Set global ingress limit to the agreed caller quota, body size 64 KiB, bounded upstream/response timeouts, and scrub externally supplied correlation headers unless the trusted-header setting is intentional. Make health probes over HTTPS. Configure WAF/log routing without body/token capture. Financial audit records contain requested_by and the originating correlation UUID in the same transaction. JSON logs contain correlation/trace IDs, valid payment ID, outcome code, status and latency; no account IDs, balances, JWTs, or raw payloads are logged.

## Monitoring

Public health probes are `/actuator/health/liveness` and `/actuator/health/readiness` (the latter checks database connectivity). Authenticated admin metrics are `/actuator/prometheus`. Track HTTP request/latency metrics and `payments.outcomes` tagged by code, `payments.errors`, `payments.processing`, `payments.replays`, `payments.retries`, and `payments.lock.timeouts`. Trace export is asynchronous; a collector outage cannot reverse a committed payment. Alert on readiness failure, 503 rate, p95 latency, lock timeout/retry growth, and any reconciliation mismatch.

## Reconciliation

Call `GET /api/v1/operations/reconciliation` with a JWT containing `payments:admin`, at least daily and after incident recovery. It returns `healthy` and issue references for completed payments without two balanced ledger entries, noncompleted payments with ledger activity, and account balances differing from immutable opening baseline plus signed ledger entries. The SQL reads one consistent statement snapshot. Baselines are established when accounts are provisioned before any transfers; subsequent funding/withdrawal must use controlled ledger-backed workflows, never arbitrary balance edits. Reconciliation is read-only and never automatically fixes money.

If mismatches exist: disable affected write traffic, preserve logs and database snapshot, identify the initiating principal/payment/correlation IDs, compare transaction and WAL records, and escalate to finance and security. Corrections use separately authorized compensating transfers with fresh IDs and audit evidence. Never edit or delete the original ledger/payment. Do not rerun instructions with a fresh ID merely because an HTTP response was lost.

## Backup and recovery

Take encrypted daily base backups and continuous WAL archives under restricted backup identity. `pg_dump --format=custom --file=payments.dump "$BACKUP_DATABASE_URL"` provides an additional logical backup. Copy backups to a separate failure domain, verify checksums and monitoring, and manage keys independently. Restore only into an isolated database using `pg_restore --dbname="$RESTORE_DATABASE_URL" --no-owner payments.dump`, run Flyway validation with matching release, run the full reconciliation report, and compare balances/payment counts and sampled receipts before cutover. Test restore quarterly and after backup/tooling changes; record measured RPO/RTO. Physical base backup plus WAL is required for point-in-time recovery; logical dumps alone do not provide it.

## Retention

Financial rows are retained indefinitely by the application. This fail-closed default is configurable operationally via an approved encrypted archive retention schedule (`FINANCIAL_RETENTION_YEARS` in the deployment policy, minimum chosen by local legal requirements). There is deliberately no automatic runtime purge. A separate privileged archive process must retain audit linkage, ledger baselines, and payment-ID tombstones for the full idempotency horizon and must never break reconciliation. Legal holds override archive expiry. Configure structured log retention at the logging backend (default policy recommendation 90 days); purge tokens/payloads at ingestion if a proxy logged them accidentally. Document jurisdiction-specific retention approval before enabling deletion.

## Performance validation

The target is p95 <500 ms at 100 instructions/second. Use an isolated reference PostgreSQL/application environment and `TOKEN=... BASE_URL=https://... k6 run scripts/load.js`. Fund the source baseline before testing; the default 0.001 transfers consume only 6 JOD in 60 seconds. Capture CPU/RAM/disk/network, DB/application versions, pool size, replicas, warmup, errors, achieved rate, dropped iterations, and k6 summary. Reconcile afterward. The load script fails thresholds on p95 or success errors. Test both cold and warm runs, contention and replay traffic. No production-hardware performance guarantee is claimed without those measurements.

## Recorded local performance run

On 2026-10-28, `python3 scripts/measure_local.py` submitted 6,000 payments over 60 seconds at 100.00 requests/second after 100 warmup transfers, using one Java 21 process (`-Xmx512m`), a pool of 20 connections, real RS256 JWT verification and PostgreSQL 16 in Docker on a 16-CPU Linux host. There were zero request failures; p50 was 4.86 ms, p95 23.74 ms and p99 56.88 ms. Accounting afterward showed 6,100 completed payments, 12,200 ledger entries, total account balance 600.000 JOD, and signed ledger sum 0.000. See `performance-result.json` for machine-readable evidence. This loopback HTTP result verifies the initial engineering target in this local reference environment; production TLS, hardware, replica and ingress behavior still require environment-specific measurements.

The isolated harness requires Python 3 plus `cryptography`, Java 21, Docker and a previously built JAR. It creates and removes its own PostgreSQL container and fresh signing keys, never uses existing accounts, and writes logs under the project directory. `LOAD_SECONDS` adjusts duration; the default is 60 seconds.
