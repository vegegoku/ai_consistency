# Operations

## Financial invariants and reconciliation

`GET /api/v1/admin/reconciliation` requires `payments:admin`. It returns `issues: []` when every completed payment has exactly one matching debit and credit totaling zero and every account equals `opening_balance + SUM(signed_amount)`. A repeatable-read snapshot avoids false positives during live transfers. Opening balances are established when accounts are provisioned and must not be changed later; fund additions require an audited provisioning/ledger process outside this release. Run reconciliation daily and after restore, incident, migration or failover. Alert on any nonempty report; freeze affected accounts using a privileged operations role, preserve evidence, and investigate. Never patch immutable ledger rows or manufacture a compensating payment without an approved business instruction.

The deferred database constraint checks completed/rejected ledger cardinality and balance at commit. Ledger updates/deletes and finalized payment updates/deletes are rejected by triggers; only the registering record inside the transfer's uncommitted transaction may be finalized. Privilege separation additionally prevents runtime TRUNCATE/DDL and opening-balance changes. No deletion endpoint exists. Audit history includes initiating principal, fingerprint, timestamp and correlation ID.

## Security and retention

Use a separate migration owner and runtime login. Apply `ops/least-privilege.sql` as DBA after provisioning `payment_runtime` with an externally managed secret. Runtime has no role management, table ownership or DDL capability. Configure network allowlists, TLS for both ingress and PostgreSQL (`sslmode=verify-full` with a trusted root), encrypted storage, secret rotation and restricted private management ingress. Grant account debit delegation through privileged audited procedures only. Destination allow-incoming and account transfer-limit policies are persisted. Back up privilege changes separately.

Financial retention is a minimum `PAYMENT_RETENTION_DAYS` policy floor (default 2555 days / seven years), subject to jurisdiction/legal holds. Read this setting in the deployment's archival workflow. The service never automatically purges financial data. Archive at or after the configured minimum only after legal review, reconciliation, checksum verification and restore testing; keep discoverability required by contract. Log/trace retention is configured independently in the collector (recommended 30 days unless incident/legal holds require more); do not store tokens, payloads, secrets or balances there. Operational issue details remain in durable rejection records. Capacity monitoring must alert before the archive floor threatens database storage.

## Backup and restore

Enable encrypted daily `pg_dump -Fc` backups plus PostgreSQL WAL archiving/PITR; select RPO/RTO with the operator. Example local drill:

```sh
docker compose exec -T postgres pg_dump -U payments -d payments -Fc > payments.backup
# Restore to a NEW isolated database, never over a live system:
docker compose exec -T postgres createdb -U payments payments_restore
docker compose exec -T postgres pg_restore -U payments -d payments_restore < payments.backup
```

Verify backup hashes and retention, run migrations/validation against the restored DB, point a separate service at it, query known completed and rejected IDs, check balances and run reconciliation. Record the time and results of each quarterly restore drill. Test process restart by stopping and starting the service without deleting the PostgreSQL volume; query a known receipt and replay its instruction, confirming unchanged balances and two ledger rows. Avoid `docker compose down -v` on data that matters.

## Incident response

If a response is lost or a commit result is uncertain, retrieve the payment ID or repeat the exact canonical instruction under that same ID. Never retry with a new ID. A stored rejection is definitive; corrected instructions use a new ID. A 503 is transient and must not become a durable business rejection. Alert on readiness failure, lock timeouts/retries, unexpected errors and reconciliation issues. Check correlation/payment IDs, DB saturation and locks; do not export tokens/full messages into tickets. Restore connectivity or reduce load, then reconcile before relaxing account freezes. All operator adjustments require a documented business authorization and immutable audit trail.

## Load measurement

Use an isolated database and reference hardware. Run the supplied k6 100 requests/sec scenario for at least one minute after warmup, then a sustained run. Grant the testing identity debit access to both accounts and raise its rate threshold; alternate direction at 0.001 JOD to avoid exhausting balances. Record p50/p95/p99, failed requests, CPU, DB waits and pool saturation. Run reconciliation before/after. Do not claim p95 <500ms based on unit tests or shared development machines. Restrict tracing sampling/collector pressure as needed; telemetry exceptions after commit never turn a committed financial transfer into a rollback.
