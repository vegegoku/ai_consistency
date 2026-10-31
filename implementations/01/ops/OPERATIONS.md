# Operations

## Database privileges and migrations

Create distinct roles for schema owner/migration and runtime. Deliver credentials through the deployment secret manager, rotate them, require TLS with server certificate verification on PostgreSQL connections, and restrict network access. Runtime must not own tables or have superuser/DDL/trigger-disable privileges. After migrations, grant runtime only:

```sql
GRANT USAGE ON SCHEMA public TO payments_app;
GRANT SELECT ON customers,accounts,account_permissions,payments,ledger_entries TO payments_app;
GRANT INSERT ON payments,ledger_entries,rate_limit_buckets TO payments_app;
GRANT SELECT,UPDATE ON rate_limit_buckets TO payments_app;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payments_app;
GRANT UPDATE(status,rejection_code,processed_at,response_body,http_status) ON payments TO payments_app;
GRANT USAGE,SELECT ON SEQUENCE ledger_entries_id_seq TO payments_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
```

Accounts/customers/grants are provisioned by a separate controlled administrative process, including verified opening balance. No ordinary application flow deletes transactional records. All schema changes are Flyway V migrations; development-only repeatable seeds are excluded from production migration locations.

## Reconciliation

Run `GET /api/v1/admin/reconciliation` with `payments:admin` daily and after failovers. A repeatable-read snapshot compares every completed payment to two entries with zero signed sum, correct accounts and exact amounts. It compares each account to `opening_balance + SUM(signed_amount)`. Save the report in controlled audit storage, alert on `MISMATCH`, and investigate before further affected-account transfers. The opening baseline is protected by an immutable trigger. Freeze affected accounts through the administrative process; do not rewrite ledger entries. Resolve with approved compensating payments and documented incident references.

Schedule the endpoint through the deployment scheduler; the service itself remains stateless. Report latency grows with ledger size; partition/archive and measure at production scale before using large retention histories. Restrict the `/q/metrics`, `/q/health/*`, and `/q/openapi` routes at the management network boundary. `/q/health/live` is liveness, `/q/health/ready` includes JDBC connectivity.

## Backups, restore drills and retention

Enable encrypted continuous WAL archiving and daily base backups; retain offsite copies. Set RPO/RTO through the business continuity policy. At least monthly restore the base backup and WAL into an isolated environment, verify Flyway schema history, reconcile all accounts/payments, and verify selected original receipts. Record actual restore time and recovery point. Never restore an old snapshot over a running writer. Stop traffic, recover to a known consistent point, reconcile, establish a single authoritative primary, and reopen traffic after incident approval.

`PAYMENT_RETENTION_DAYS` defines the minimum archival policy period (default 2555 days); deployment retention jobs must read this value and apply longer legal holds/jurisdictional requirements. This service retains financial history indefinitely and intentionally exposes no purge function. Archive only as an approved offline procedure that preserves ledger/opening-baseline reconciliation and unique-ID deduplication history. Application/audit logs are retained externally with configurable collector policies (recommend 90 days searchable, minimum seven years initiating-principal/payment outcome audit according to local legal review); never log tokens, full requests or balances. Raw malformed payloads are neither logged nor persisted. Audit initiator, correlation and outcome are persisted on payments, including rejected instructions.

## Incident handling

For response loss, client timeout, DB disconnect or 503, query the original payment ID. A found COMPLETED outcome is definitive; retry same canonical instruction with the same ID when status is unknown. Never manufacture a new ID for automatic retries. Business rejections are definitive and immutable. Compare request correlation IDs with the stored payment correlation and operational logs; do not request access tokens from clients. Check row-lock timeout/retry metrics, request latency, idempotent replays, pool and DB health. Notifications and telemetry are outside the financial commit boundary.

For suspected data corruption: restrict writes, snapshot evidence, run reconciliation, identify source of privileged mutation, revoke compromised credentials, restore or compensate under incident control. Do not disable financial integrity triggers in ordinary operations. Direct SQL grants and backup tooling are deployment responsibilities; local Compose uses a database owner solely for development/tests.

## Load target

Use a representative dedicated environment with TLS/OIDC, production database topology, telemetry and sufficient provisioned source funds/daily limits. Run `TOKEN=... BASE_URL=https://... SOURCE=... DESTINATION=... k6 run scripts/load.js`. It requests 100 transfers/sec for 60 seconds and asserts p95 under 500 ms and <1% failed requests. The file `load-results.json` records results. Set the rate policy above the offered volume and repeat across replicas/hot versus distributed accounts. A developer workstation result does not establish a production SLA.
