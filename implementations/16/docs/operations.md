# Operations

## Reconciliation

Establish every account's immutable opening balance during controlled provisioning. Available balance must equal opening balance plus all signed ledger entries. Every completed payment must have two opposite entries matching its source, destination and exact amount. `GET /api/v1/admin/reconciliation` returns invariant violations in one repeatable-read snapshot and requires `payments:admin`. An empty `issues` array means the checks found no discrepancy. Schedule this authenticated read at least daily and after restores or incidents; page the financial operator on any nonempty report. Tests deliberately corrupt a balance and verify detection. Do not repair a discrepancy by updating ledger entries or opening balances. Investigate against audit records and post an approved compensating transaction.

## Deployment and privileges

Use stateless application replicas behind a gateway, one authoritative PostgreSQL cluster, transaction-capable connections and durable WAL. Give each application instance runtime privileges from `scripts/runtime-grants.sql`, separate from the migration owner. Never grant runtime roles DDL, table ownership, TRUNCATE, DELETE or ledger UPDATE privileges. Apply migrations before serving new code; Flyway version history and checksums protect deployed schema. Account provisioning and grants are controlled operator actions with independent audit trails; no public admin write API exists.

Load connection and JWT secrets from a secret manager. Restrict PostgreSQL ingress to application/migration/backup identities. Configure encrypted connections, TLS certificates, regular credential rotation, connection limits and statement timeouts. Public liveness/readiness show no connection details. Restrict Prometheus and reconciliation to operational identities. Treat correlation headers as untrusted unless a trusted gateway owns them; generated IDs are the default.

## Monitoring

Scrape `/actuator/prometheus` with an admin JWT. Inspect HTTP request counts and latency, `payments.outcomes` tagged by status/error code, `payments.completed`, `payments.rejected` by code, `payments.processing`, `payments.replays`, `payments.retries`, `payments.db.lock_timeouts`, and `payments.unexpected_failures`. `payments.db.transient_failures` counts transient database failures, while the lock-timeout counter specifically counts PostgreSQL lock-timeout errors. Structured ECS JSON logs include correlation ID, valid payment ID, HTTP outcome, error code, initiator and latency; the database stores the original initiating principal. Full payloads, account balances, JWTs, credentials, SQL and exception stacks are excluded from API responses. Protect startup logs and configure production log access and retention. OpenTelemetry propagates HTTP traces to the configured collector; telemetry emitted after commit cannot undo committed transfers.

## Backup and restore drills

Choose RPO/RTO with the service owner; enable continuous WAL archiving/PITR and encrypted off-site backups with restricted access. At minimum, schedule daily `pg_dump --format=custom --no-owner --file=payments.dump "$BACKUP_DATABASE_URL"` and retain encrypted snapshots under the same legal policy as financial records. Pass credentials through `.pgpass` or a secret provider, not shell history. Monitor backup completion and test checksums.

At least monthly restore into an isolated fresh PostgreSQL instance using `pg_restore --exit-on-error --no-owner --dbname="$RESTORE_DATABASE_URL" payments.dump`; replay required WAL for the selected recovery point, run Flyway validation, run reconciliation and compare payment/ledger counts to the backup manifest. Record recovery duration and achieved recovery point. Verify that the restored app can retrieve known receipts and that retrying a known ID has no financial effect. Never restore over the live primary as an unreviewed routine operation. Keep the old primary fenced during failover to prevent split-brain writes.

## Incident handling

On a database outage or rising 503/lock-timeout rate, stop admitting new transfers if needed, retain payment IDs, and query outcomes after connectivity returns. A timeout may follow a successful commit; replay the same logical ID and payload. Transient failures never become permanent business rejections. Validate readiness, primary health, lock waiters, pool saturation and database storage. Infrastructure retries are bounded and never replace IDs.

For a ledger discrepancy, preserve logs and backups, restrict transfer ingress, reconcile, identify the affected IDs and involve the financial operator. No direct edits or deletions of financial records are an application recovery flow. Compensating payments have their own IDs and approval/audit records. After recovery reconcile again, verify idempotent retries and document the incident timeline using correlation IDs. Never expose customer details or tokens in tickets.

## Retention

`AUDIT_RETENTION_DAYS` defaults to 2555 days (seven years). This is a configurable operational retention baseline, subject to jurisdictional and contractual review and legal holds. Keep payments, ledger entries, immutable opening baselines, relevant principal/audit records and encrypted backups for at least that duration; centrally configure log retention consistently with privacy requirements. The application deliberately has no automatic destructive purge. Operators implement archival jobs from this configured policy, preserving referential integrity and retrieval ability, and may remove expired archives only after legal holds and retention obligations have been checked. Verify archival restore as part of restore drills.

## Performance

Run `mvn verify -Dpayments.load=true` on a representative host and archive `target/load-result.txt` with CPU/memory, Java/PostgreSQL versions, pool size and network topology. This schedules 100 requests/second for 10 seconds and verifies 1,000 committed transfers, p95 under 500 ms, and post-load reconciliation. The default suite omits this optional timed load check to avoid making shared CI performance nondeterministic. For a longer deployed reference test use `TOKEN=... RATE_PER_MINUTE=12000 k6 run scripts/load.js` after configuring the service rate allowance and funded fixture accounts. A production claim requires representative multi-replica tests, gateway limits, database tuning, and backup/restore exercises; one workstation run does not establish that guarantee.
