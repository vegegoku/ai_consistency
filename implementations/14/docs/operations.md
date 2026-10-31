# Operations

## Reconciliation

Call GET /api/v1/admin/reconciliation with payments:admin. A clean report is {"issues":[]}. The report uses a repeatable-read snapshot and checks completed payment entry count, matching source/debit and destination/credit signs and amounts, net zero, absence of rejected-payment ledger entries, opening balance plus signed ledger sums against persisted balances, and each historical balance_after against its running ledger baseline. Schedule a read-only daily job at the operations platform; alert on nonempty reports and store the report in restricted audit storage. No automatic repair is performed.

Baseline is accounts.opening_balance at provisioning; fund additions must be modeled by a reviewed ledger migration, never by direct runtime UPDATE. Service role cannot modify that baseline or financial history. Administrative correction requires a separately authorized compensating transaction with its own identity and audit trail.

## Backups and restore

Use encrypted daily base backups plus PostgreSQL WAL archiving/PITR to restricted storage. Configure retention according to RPO/RTO and legal jurisdiction. Example logical backup: pg_dump --format=custom --no-owner "$DB_URL_PG" > payments.dump (DB_URL_PG is a libpq connection string supplied by secret management). Do not put passwords on command lines or commit backups.

Restore to a new isolated PostgreSQL database using pg_restore --no-owner --dbname="$RESTORE_DB_URL_PG" payments.dump, apply any missing Flyway migrations with the migration role, run readiness and reconciliation, compare counts/totals with the backup manifest, and run a transfer/replay smoke test using test-only accounts. Perform and document quarterly restore drills with measured recovery time. Never restore over a live writer. Fence old writers before a production failover to prevent split-brain.

## Incident handling

For timeout/unknown commit outcome, query GET payment status with the original ID and principal, or replay exactly the original command. No new ID unless the original is definitively rejected and the business instruction is corrected. For DB outage, readiness fails and requests return retryable 503; investigate connectivity, capacity and lock metrics. Do not mark infrastructure failures REJECTED. For reconciliation mismatch, stop new payments at ingress, preserve DB/WAL/log evidence, investigate authorized changes and restores, compare opening baselines and ledger running balances, and use reviewed compensations. Do not delete or rewrite ledger entries.

## Retention and audit

AUDIT_RETENTION_DAYS defaults to 2555; operations must set it to the jurisdiction-approved minimum (and honor legal holds), document review and archival schedules, and retain payments, ledger and relevant audit evidence accordingly. It is a policy configuration, not a deletion scheduler. Financial and payment-issue rows are never deleted by this service. At the retention boundary export signed/encrypted archives, verify restore/reconciliation, obtain required records-owner authorization, and use separately controlled archival tooling. Logs/metrics retention belongs to the observability platform and must exclude payloads/secrets. Configure access and retention centrally, preserving correlation and payment identifiers only for authorized operators. Backups inherit the same confidentiality and retention controls.

Monitor payments_requests_total, payments_completed_total, payments_rejected_total by code, payments_request_latency_seconds, payments_replays_total, payments_retries_total, payments_lock_timeouts_total and payments_failures_total. Alert on readiness failure, elevated 503/500, p95 latency and reconciliation issues. Telemetry is emitted after transaction commit and cannot reverse an approved transfer.
