# Operations

## Reconciliation

`GET /api/v1/admin/reconciliation` requires a JWT with `payments:admin`. It takes a repeatable-read snapshot, compares each completed payment to two exact signed account-matching ledger entries, and checks each balance against its immutable opening-balance baseline plus ledger sum. Response `consistent=false` contains identifiers restricted to operators. Call hourly from the operations scheduler and after restores/incidents. A nonempty report pages the financial on-call; prevent further debits for affected accounts using the authorized account-administration process.

```sh
OPERATOR_TOKEN=$(python3 scripts/dev-token.py operator payments:admin)
curl -H "Authorization: Bearer $OPERATOR_TOKEN" https://service.example/api/v1/admin/reconciliation
```

Database constraints additionally reject commits containing incomplete payments, unbalanced/missing ledger pairs, or mismatched debit/credit accounts or amounts. Ledger updates/deletes and final payment updates/deletes are blocked by triggers. Reconciliation never repairs financial state. Opening balances must be established before an account accepts transactions; any historical import must reconcile under dual control. No account creation or administration endpoint is exposed by this service.

## Backups and recovery

Use encrypted daily PostgreSQL base backups plus continuous WAL archiving with point-in-time recovery. Keep backup encryption keys separate from the database and restrict backup access. Monitor archiving failures, backup age and restore duration. Define deployment RPO/RTO before release (recommended RPO <5 minutes, RTO <1 hour).

A logical local backup example, using secret-managed `PGPASSWORD`:

```sh
pg_dump --host=localhost --username=payments_owner --format=custom --file=payments.dump payments
createdb --host=localhost --username=payments_owner payments_restore
pg_restore --host=localhost --username=payments_owner --dbname=payments_restore payments.dump
```

Restore into an isolated database, never over a live primary. Validate migration checksums and row counts, run reconciliation and selected payment ID queries, verify ownership/permissions, then perform a controlled failover. Do not replay external instructions using new IDs. Conduct and document a restore drill at least quarterly. Actual backup automation and external storage are deployment responsibilities.

## Incident handling

For a timeout, lost response or 503, preserve the original paymentId and correlationId. Query the result and retry the identical command only with that ID. A 404 after an uncertain failure permits an identical retry; a durable rejection requires a new ID for a corrected instruction. Never assume 503 means no commit occurred.

On ledger mismatch: restrict affected debit activity, collect receipt IDs/correlation IDs and database snapshot, identify the last known reconciled baseline, and involve finance. Do not update ledger rows, edit completed payments or manually overwrite balances. Use a reviewed compensating transfer with a distinct identity and clear purpose when appropriate.

On DB outage: readiness becomes unavailable and requests fail safely. Restore DB connectivity and inspect status before manual retries. On lock spikes: inspect long-running transactions, blocked sessions, PostgreSQL CPU/I/O and connection pool utilization; do not remove lock or integrity constraints to improve latency. On compromised credentials: revoke issuer credentials and DB credentials, rotate verification keys/JWKS, and audit initiating principals and affected time windows.

## Telemetry and audit

Structured JSON logs contain safe correlationId, valid paymentId, status, code and latency, with no bodies/tokens/customer details. Payments persist requested_by, correlationId, instruction and processing timestamps and rejection code. Ledger entries link payments and account balance-after snapshots. Metrics include payment requests/outcomes, completion/rejection by code, latency, replay, retries, lock timeouts and unexpected failures; standard HTTP metrics and OpenTelemetry HTTP tracing are enabled. Telemetry occurs after commit and cannot undo money movement. If exporters are unavailable, investigate dropped telemetry separately.

Production ingress must restrict `/q` endpoints to operations networking, preserve TLS, strip untrusted forwarded and correlation headers, and impose an unauthenticated IP/global request limit before JWT processing. The application enforces a database-shared per-principal fixed-minute limit. Requests over the limit return 429 with Retry-After. Retire inactive `principal_rate_limits` rows older than one day during maintenance using the migration/maintenance role; the runtime role cannot delete financial data.

## Retention and access

`PAYMENT_RETENTION_YEARS` declares the deployment's documented minimum payment/ledger/audit retention policy (default seven years). Configure archival and storage lifecycle in the operational backup system; the application deliberately provides no purge endpoint or financial-table TTL. Obtain legal approval for jurisdiction-specific retention and legal holds. Retain operational logs for the organization's configured period (recommended 90 days online plus controlled archive) through collector settings; do not persist raw payment payloads. Audit privileged access and key rotation. Runtime `payments_app` is separate from migration owner, and backup/restore roles are separate again. Access to customers, account grants, opening baselines, archives and reconciliation is privileged.
