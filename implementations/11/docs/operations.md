# Operations runbook

## Reconciliation

Run `GET /api/v1/admin/reconciliation` with `payments:admin` at least daily and after restores/incidents. An empty `issues` array is healthy. `LEDGER_PAIR` flags completed payments lacking two balanced entries; `BALANCE` flags persisted balance != opening balance + signed ledger sum. The endpoint is read-only, uses a single SQL snapshot, and never corrects financial data. Opening balances are immutable operational baselines established before transactional use. Provisioning/top-up must be a controlled audited ledger process with an explicitly reviewed baseline, not an ad hoc balance edit.

Deferred PostgreSQL triggers also enforce exactly two entries, debit/credit account identity and signed amounts for every completed payment, and no ledger entries for rejection. Database triggers reject updates/deletes of finalized payments and ledger rows. The application has no financial delete API. Investigate any discrepancy before releasing further transfers; isolate affected accounts, preserve forensic data, reconcile against backups and originating instructions, and post reviewed compensating transfers rather than changing historical records.

## Retention and access

Default financial/audit policy is seven years (`AUDIT_RETENTION_DAYS=2555`, adjustable to jurisdiction and contractual duties). `GET /api/v1/admin/retention` reports records older than the configured minimum and the policy, for an archival workflow; it never deletes data. Configure log backend retention independently to the same approved policy, encrypt archival storage, retain legal holds, and restrict access. Immutable online records remain until an authorized, reviewed archive process exports, verifies checksums, and reconciles them; ordinary application credentials cannot delete them. A policy age is a minimum, not an automatic erasure schedule. Operators must obtain the applicable legal retention decision before deployment.

Use separate owner/migration/runtime database identities. Example after migration (replace role names via controlled infrastructure):

```sql
CREATE ROLE payment_runtime LOGIN;
GRANT CONNECT ON DATABASE payments TO payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_permissions,payments,ledger_entries,payment_issues TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT INSERT ON payments,ledger_entries,payment_issues TO payment_runtime;
GRANT UPDATE(status,rejection_code,processed_at,http_status,response_json) ON payments TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
```

Assign the runtime password from a secret manager, never source control. Disable in-process migrations (`MIGRATIONS_ENABLED=false`) after an external migration job, or supply separate `MIGRATION_USER`/`MIGRATION_PASSWORD`. The runtime role must not own tables, be a superuser, disable triggers, create roles, or modify opening balances/permissions. Rotate keys and secrets; use encrypted database transport with CA validation in DB_URL. JWT keys are fetched via trusted JWKS, with issuer, audience, RS256 signature and expiry checks. Terminate public traffic with TLS; restrict actuator/admin endpoints at the network and JWT layers. Apply a shared gateway rate limit for stateless replicas and cap connection pools across replicas against PostgreSQL capacity.

## Backup and restore

Use managed PostgreSQL continuous WAL archiving and encrypted point-in-time recovery plus daily logical backups. Establish explicit RPO/RTO with the operations owner. Sample logical backup using a credential file or secret-injected connection, not credentials on the command line:

```sh
pg_dump --format=custom --file=payments.dump --dbname=payments
pg_restore --list payments.dump
# In an isolated recovery database:
createdb payments_recovery
pg_restore --single-transaction --no-owner --dbname=payments_recovery payments.dump
```

Back up schema, Flyway history, customers, permissions, baselines, payments, ledger and issue records together. Test a restore at least quarterly: restore to an isolated instance, run Flyway validation and reconciliation, verify representative completed/rejected/replayed IDs, check account totals and access policies, record measured recovery time, and destroy the recovery environment after review. Never test destructive recovery against the primary. Persist container volumes in development; production uses managed durable storage and backups.

## Incidents

For timeout/connection loss after submission, query by the same payment UUID or retry the identical instruction. Only the committed database record establishes outcome. A `503` is transient; do not replace the ID. Business rejections are durable and corrections require a new UUID. Unique registration and deterministic row locking work across replicas. Lock wait is 1.5 seconds, statement timeout 5 seconds, transaction timeout 10 seconds; at most two additional attempts for PostgreSQL deadlock/serialization/lock-timeout states. Infrastructure failures roll back registration and account/ledger changes. Never classify them as durable business rejections.

If readiness fails, remove the replica from serving, check PostgreSQL health/capacity/credentials/TLS and lock holders, and restore connectivity. Keep liveness separate to avoid restart storms caused by database outages. For lock storms, inspect `pg_stat_activity`/`pg_locks` with an operations identity and reduce request admission; do not kill sessions indiscriminately. For suspected duplicate or missing effects, preserve correlation IDs/payment IDs and run reconciliation; do not edit balances. Search structured logs by correlation ID, never collect access tokens or full messages.

Monitor `http.server.requests` (request count/latency), `payments.completed`, `payments.rejected` tagged by bounded error code, `payments.replays`, `payments.processing`, `payments.retries`, `payments.lock.failures` and `payments.unexpected.failures`. Prometheus names translate dots to underscores. OTLP is best effort; tracing/logging outages never participate in database transactions. Alert on sustained 5xx, readiness loss, increased lock failures/retries, rejection spikes, p95 latency target misses, and any reconciliation discrepancy. Logs contain no balances, customer names, account IDs, secrets or full payloads. Financial audit principal and original correlation ID are persisted with each payment.
