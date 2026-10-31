# Operations runbook

## Reconciliation

Use an operator token with `payments:admin` for `GET /api/v1/admin/reconciliation`. The report uses one repeatable-read database snapshot, verifies exactly one correct debit and credit of the specified amount for every completed payment, verifies zero signed sum, and compares each available balance with its immutable establishment baseline plus ledger sum. A baseline is the initial settled balance recorded in `accounts.opening_balance`; external balance mutations are forbidden. The report identifies mismatches without repairing them. Run at least daily and after restore or incident; alert when `consistent=false`. Keep this endpoint private.

Do not repair discrepancies by deleting entries or rewriting receipts. Stop payment admission, preserve database snapshots and audit logs, identify the failed invariant, and apply an independently reviewed compensating accounting procedure with a new audited identity. Keep rejected IDs reserved permanently. A missing destination's identifier can be stored in rejected payments, so payment account columns intentionally have no account FK; completed ledger rows have restrictive account and payment FKs.

## Backup and recovery

Use encrypted daily PostgreSQL base backups plus continuous WAL archival for point-in-time recovery. Store backup keys separately, limit access, test restore monthly, and record RPO/RTO according to the operating organization's obligations. For a local logical backup:

```sh
docker compose exec -T postgres pg_dump -U payments -Fc payments > payments-backup.dump
# Restore into a new, isolated empty database, never over the active service:
pg_restore --no-owner --dbname="$RESTORE_DATABASE_URL" payments-backup.dump
```

Verify Flyway version, row counts, readiness and reconciliation after restore. Restart the service against the restored database and query a known payment receipt; retry its exact payload and confirm no additional ledger rows or balance changes. Promote the restored database only after these checks and authorization tests. Do not expose backup dumps in build artifacts.

## Incident handling

For a timeout after submission, first query the original payment ID, then retry the exact canonical instruction with that same ID. Never generate a new ID to resolve uncertainty. 503 is transient; a durably REJECTED result is definitive and any corrected payment needs a new ID. If PostgreSQL fails, admission fails safely before any financial effect or rolls back the transaction; an ambiguous commit remains discoverable by ID when connectivity recovers.

Monitor `payment_http_requests`, `payment_http_latency`, `payment_outcomes`, `payment_replays`, `payment_retries`, `payment_lock_timeouts`, and `payment_unexpected_failures` (Prometheus adds counter suffixes). Investigate sustained 503, p95 above 500 ms, lock retries or reconciliation mismatches. Use correlation IDs to match API logs to `payments.correlation_id` and `requested_by`. Logs deliberately exclude body, JWT and balances.

## Retention and permissions

Financial records are retained indefinitely by default; no automated destructive purge exists. Set documented organizational financial retention in `FINANCIAL_RETENTION_DAYS` (0 means indefinite), legal holds and jurisdiction-specific minimums before commissioning any archival process. This variable documents deployment policy; it never enables destructive deletion. Keep payments, ledger and initiator audit together in immutable encrypted archives. An approved archival process must preserve idempotency tombstones and opening balance reconciliation. Logs have independent deployment-controlled retention through `LOG_RETENTION_DAYS` (default 30 in the example policy); configure the log collector's expiry and legal holds accordingly. Neither setting authorizes deletion through this API.

Run the application with a role that cannot delete, truncate, alter schema or disable triggers. Use a separate migration role, rotate secrets, enforce TLS to the database in production (`sslmode=verify-full`), and monitor privileged access. Runtime grants are in `ops/runtime-grants.sql`. Restrict outbound network access to approved database, identity/JWKS and telemetry destinations.
