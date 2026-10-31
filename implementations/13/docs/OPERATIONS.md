# Operations

## Financial reconciliation

Run at least daily and after deploy, restore, or financial incidents. Use a restricted principal with `payments:admin` scope:

```sh
ADMIN_TOKEN=$(python3 scripts/dev-token.py operations payments:admin)
curl -f http://localhost:8080/api/v1/admin/reconciliation -H "Authorization: Bearer $ADMIN_TOKEN"
```

Production uses your identity provider, HTTPS, and an administrator identity; the local script signs development tokens only. The report uses one repeatable-read database snapshot. It verifies exactly two signed entries per completed payment, correct account/amount references, zero signed total, and each account's `opening_balance + sum(ledger.signed_amount) = available_balance`. `opening_balance` is a controlled baseline set only when provisioning an account. Existing balances must be imported with an independently reconciled baseline. A nonempty report is an incident; stop submissions for affected accounts and preserve evidence. Never edit or delete ledger rows to make a discrepancy disappear. Correct financial mistakes with an approved compensating transfer under a new payment ID.

The report is a read-only operational task exposed through an authenticated endpoint. Schedule your operations runner to call it, archive the signed/dated result, and alert on nonempty issues or HTTP failure. Account IDs in reports are privileged operational data.

## Least-privilege database deployment

Use separate migration and runtime credentials. The schema owner executes Flyway before enabling traffic. Then disable runtime migration and grant only the required privileges (replace the role name with your managed runtime role):

```sql
GRANT CONNECT ON DATABASE payments TO payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers, accounts, account_permissions, payments, ledger_entries TO payment_runtime;
GRANT INSERT ON payments, ledger_entries TO payment_runtime;
GRANT UPDATE(status,rejection_code,rejection_http,processed_at) ON payments TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT SELECT, INSERT, UPDATE ON rate_windows TO payment_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
```

Do not grant DELETE, TRUNCATE, schema DDL, ownership, customer/permission management, opening-balance modification, or ledger UPDATE. Security/account provisioning belongs to a separately authorized operations process. RESTRICT foreign keys prevent deletion of referenced records; immutable triggers protect final payments and ledger rows even from accidental SQL updates. A DBA can bypass application safeguards, so privileged DB access must be separately audited.

## Backup and restore

Set an explicit recovery objective with the organization. A reasonable initial operating policy is daily encrypted full backups plus continuous WAL archiving/PITR, RPO under 5 minutes and RTO under 1 hour, subject to measured restore drills. Use managed PostgreSQL backups or `pg_basebackup` plus WAL archiving for PITR; a logical dump alone cannot meet continuous recovery objectives.

Local logical-backup rehearsal:

```sh
mkdir -p backups
docker compose exec -T db pg_dump -U payments -d payments -Fc > backups/payments.dump
# Restore only into a new disposable database, never over the live database.
docker compose exec -T db createdb -U payments payments_restore
docker compose exec -T db pg_restore -U payments -d payments_restore --exit-on-error < backups/payments.dump
```

Protect backup files and checksums with encryption and restricted access; transfer them to separate failure domains. Restore drills must verify Flyway version, payment/ledger counts, the reconciliation report against the restored DB, and a known payment receipt. Perform monthly drills and after backup, encryption-key, or database upgrades. Record elapsed time and reconcile it with the RTO. Destroy disposable restore environments under the same retention/access policy as financial data.

## Failure/incident playbook

1. Preserve payment ID, current correlation ID, time window, code, and initiating principal from audited records; do not collect bearer tokens or full user payloads in tickets.
2. Query the exact payment ID as the original principal. COMPLETED means funds moved. REJECTED means no funds moved. 404 or 503 after a lost response requires a retry of the same ID and canonical payload once service health recovers; do not issue a new ID automatically.
3. For DB outages or lock saturation, fail readiness, limit incoming traffic, inspect PostgreSQL sessions and pool metrics, and recover the database. Each transaction has bounded locks/statements and safe rollback. Multiple application replicas coordinate through PostgreSQL.
4. For reconciliation failure, freeze affected accounts via the privileged account-management process, preserve immutable evidence/backups, and escalate to finance/security. Never manually apply one side of a transfer.
5. For signing-key compromise, revoke/rotate the identity-provider key, refresh JWKS, invalidate affected tokens, and audit `payments.requested_by`. Production must never trust the local key or use the local profile.
6. After recovery, rerun reconciliation, replay uncertain IDs, check health/latency/retries/rejections, and document the root cause.

## Retention and privacy

Application flows never delete payments, ledger, or account baselines. Financial records are retained indefinitely by default, preserving idempotency across process restarts and long outages. `PAYMENT_RETENTION_MINIMUM_YEARS` (default 7) is the minimum archival-policy setting reported by the admin endpoint; legal/compliance owners may require longer retention or legal holds. Archive financial records only with approved external tooling, consistent snapshots, linked ledger/payment retention, checksums, restored reconciliation, and an online durable ID/fingerprint/receipt registry. Deleting an idempotency key would allow an old instruction to execute again, so this service intentionally provides no financial purge endpoint or automatic purge job.

`PAYMENT_AUDIT_LOG_RETENTION_DAYS` (default 90) records the log collector policy reported to operators. Configure your collector/object-store lifecycle with that value and any legal hold. The application emits stdout JSON and does not own the collector's storage. Payment rejections retain only canonical business fields, safe code, principal, correlation UUID, and timestamps. Malformed raw bodies are never persisted. Do not log SQL bind values, access tokens, credentials, account balances, or full payloads.

Rate counters retain one current minute window per subject; their size is bounded by the population of authenticated subjects. External cleanup may remove inactive rate-counter rows; it must not touch financial records.

## Telemetry and capacity

Metrics include request count by HTTP/code, completed/rejected payments, processing latency, idempotent replays, transient retries, lock timeouts, and unexpected failures. Trace export uses OTLP, preserving standard incoming trace context through Quarkus instrumentation. Server-generated correlation UUIDs are request-specific; a stored business rejection preserves its original body correlation ID while the response header identifies the current request.

Suggested alerts: readiness down, any nonempty reconciliation report, unexpected failures, growing 503/retry/lock-timeout rate, and p95 above 500 ms at the reference 100 requests/s. Export metrics and traces only to trusted infrastructure. Logs contain structured correlation ID, valid payment ID, status/code, and latency. They do not contain secrets, balances, customer names, or full messages.

For horizontal scaling, keep replicas stateless, use a common PostgreSQL primary, and size total connection pools to the DB capacity. Keep Flyway migration execution singular. Test representative account contention, JWT verification cost, TLS, and production telemetry; local measurements do not establish production capacity.
