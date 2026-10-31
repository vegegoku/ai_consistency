# Operations

## Transaction behavior

`INSERT ... ON CONFLICT` claims the UUID under PostgreSQL's primary key. A concurrent duplicate waits for the winner, then reads its committed immutable fingerprint/result. New instructions lock both account rows in ascending order, authorize under the locks, update balances, create signed debit/credit ledger entries and seal the payment. Deferred database constraint triggers reject unfinished or unbalanced payments at commit. Business rejections commit with zero ledger entries; infrastructure failures roll back the entire transaction. PostgreSQL crash recovery protects commit durability; configure `fsync=on`, `synchronous_commit=on`, tested durable storage, and synchronous replication when the availability design requires zero data loss.

Receipts and rejection details are immutable. Application processes have no ordinary delete or edit flow for financial records. The database trigger also rejects ledger UPDATE/DELETE, including accidental administrator queries. Database owners can override protections and must be controlled and audited independently.

## Roles and deployment

Use a migration role owning the schema and a distinct runtime role. Run Flyway migrations from the same artifact with migration credentials before rolling out; then disable startup migrations for runtime replicas. Example runtime grants (replace role names and rotate the example password outside SQL source):

```sql
GRANT CONNECT ON DATABASE payments TO payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,debit_grants,recipient_restrictions,payments,ledger_entries TO payment_runtime;
GRANT INSERT,UPDATE ON payments,accounts TO payment_runtime;
GRANT INSERT ON ledger_entries TO payment_runtime;
GRANT SELECT,INSERT,UPDATE ON rate_limits TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
```

Do not grant TRUNCATE, DELETE, schema ownership, superuser, or role-management privileges. Restrict account modifications further through column grants and controlled provisioning roles. Only an account provisioning workflow may create opening balances; initial baseline and matching deposits must be established by the ledger migration policy, never by customers. In this initial service account creation is operational SQL, outside the public API.

Use a secret manager, TLS to the identity provider and PostgreSQL (`sslmode=verify-full` with trusted roots), HTTPS on public endpoints, encrypted backups and disks, and network restrictions for metrics/health/OpenAPI. The default local Compose password is for disposable local development only.

## Reconciliation

An administrator with an `admin` JWT group calls `GET /api/v1/admin/reconciliation`. It reports completed payments with missing/unbalanced entries and accounts whose persisted balance differs from `opening_balance + SUM(signed_amount)`. The report uses a repeatable-read snapshot so a concurrent committed transfer cannot create a false mismatch between its queries. No repair is automatic. Schedule this report through the operations scheduler at a suitable interval and retain its signed output. Run daily at minimum and after restore or incident recovery.

If discrepancies occur, suspend affected debits through account status controls, preserve logs and backups, identify the initiating principal and payment IDs, compare the immutable ledger to database audit/WAL records, and escalate to the financial operations owner. Do not edit a completed payment or ledger entry. Corrections require approved compensating transfers with new IDs.

## Backup and restore

Enable continuous PostgreSQL WAL archiving and point-in-time recovery appropriate to the deployment RPO/RTO. Take a daily encrypted full backup and verify checksums. A local logical snapshot example is:

```sh
mkdir -p backups
docker compose exec -T postgres pg_dump -U payments -d payments -Fc > backups/payments.dump
# Restore into an isolated fresh database, never overwrite the live database:
docker compose exec -T postgres createdb -U payments payments_restore
docker compose exec -T postgres pg_restore -U payments -d payments_restore --exit-on-error < backups/payments.dump
```

Before promotion, run schema-version checks, reconciliation against the restored database, sample receipts and account balances, and an isolated test transfer/replay. Record duration and recovery point. Perform a restore drill at least quarterly and after major storage or database changes. Avoid promoting a restored database behind accepted external commits without reconciling those payment IDs.

## Retention and housekeeping

Deployment policy parameters `FINANCIAL_RETENTION_YEARS` (example 7), `AUDIT_RETENTION_DAYS` (example 365), and `RATE_WINDOW_RETENTION_HOURS` (example 24) belong to the deployment's controlled backup/log/lifecycle jobs; the application never automatically deletes financial history. Choose periods with the legal owner for the jurisdiction and record the approved values. Retain payment, ledger, and opening baselines together; an archive must preserve idempotency lookup semantics before online history is moved. Hold all relevant data during an incident/legal hold. Configure log retention in the deployment log sink; avoid raw messages or tokens in any retained log.

Run `DELETE FROM rate_limits WHERE window_start < now() - interval '24 hours'` from a dedicated housekeeping role daily. Rate rows are operational counters, not financial/audit records. Application runtime receives no delete privilege. Monitor table size; long-lived unbounded counters must not be allowed to exhaust storage.

## Observability and incidents

Liveness `/q/health/live`, database-aware readiness `/q/health/ready`; metrics `/q/metrics`. Counters include request statuses, outcomes by bounded status/code, idempotent replays, transient retries, lock timeouts, unexpected database failures; processing timer records latency. Trace propagation uses Quarkus OpenTelemetry. Logs have valid payment ID, correlation ID, HTTP status and latency; stored payments include the authenticated initiator, correlation ID and UTC timestamps. Logs do not contain balances, source/destination identifiers, bearer tokens, or uncontrolled payloads.

For timeouts or 503s, query the payment UUID or retry its identical normalized instruction. Never retry under a new UUID. If PostgreSQL is down, readiness fails and no in-memory balance updates are possible. Investigate `pg_stat_activity`, blocking locks, statement timeouts, pool saturation, storage health and replication lag. Keep lock retries bounded; increasing retry counts can amplify outages. A restored receipt is authoritative for a committed transfer even when an HTTP response was lost.

## Performance verification

Run `scripts/load-test.py` with `BASE_URL`, `TOKEN`, `FROM_ACCOUNT`, `TO_ACCOUNT`, `LOAD_SECONDS` (default 30), `LOAD_RPS` (default 100). Use funded dedicated accounts and configure the token rate limit above the target. The test sends unique 0.001 JOD payments at an offered 100 requests/second, records errors and p95 client-observed latency, and fails above 500ms or on any unsuccessful response. Configure representative production hardware, TLS, database durability/replication, and network. Results are environment-specific, not a release performance guarantee. Save the output with CPU, RAM, database version, connection-pool configuration and target deployment revision.
