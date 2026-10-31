# Payment service operations

## Reconciliation

Run reconciliation on a schedule and after restore/incident recovery using a dedicated JWT with `operations:read`:

```sh
OPERATOR_TOKEN=$(python3 scripts/create-dev-token.py operator) # local development only
curl -fsS -H "Authorization: Bearer $OPERATOR_TOKEN" https://payments.example/api/v1/operations/reconciliation
```

The read-only report uses one PostgreSQL repeatable-read snapshot and returns `consistent`, mismatching `paymentIds`, mismatching `accountIds`, and `checkedAt`. Each completed payment must have exactly one debit and one credit, matching the source/destination and payment amount, with signed sum zero; rejected payments must have no entries. Each account must equal its immutable opening balance plus the sum of all immutable signed ledger entries. Opening balances are established only during account provisioning and cannot be edited afterward. Provision accounts and their baseline in one controlled transaction. Transfers alone do not change the total balance of the two accounts.

An inconsistent report is a financial incident: stop payment admission at ingress, preserve database snapshots/logs, investigate referenced IDs, and reconcile with an independent financial source. Do not rewrite ledger entries, manually adjust balances, truncate tables, or reuse a payment ID to hide a discrepancy. Corrections must use reviewed compensating transactions with a new identity. Test fixtures may deliberately change a balance as the database owner to demonstrate detection; runtime roles must not have broad administrative privileges.

## Database roles and migrations

Use a migration owner separate from the runtime account. Grant CONNECT on the database and USAGE on the schema; the runtime grants below are intentionally narrower than ownership. Replace `payments_runtime` with the actual role and provision its secret out of band:

```sql
GRANT USAGE ON SCHEMA public TO payments_runtime;
GRANT SELECT ON customers, accounts, account_permissions, payments, ledger_entries TO payments_runtime;
GRANT INSERT ON payments, ledger_entries TO payments_runtime;
GRANT UPDATE(status,rejection_code,rejection_http,processed_at) ON payments TO payments_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payments_runtime;
GRANT SELECT, INSERT, UPDATE ON rate_windows TO payments_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO payments_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
```

The runtime role has no DELETE/TRUNCATE, no customer/grant provisioning permission, no baseline permission, and no UPDATE on ledger entries. Trigger guards also protect privileged accidental updates. Restrict privileged account/customer/grant provisioning to separate operational processes. Use TLS to PostgreSQL (`sslmode=verify-full` plus trusted certificates), private network rules, and a managed secret store. Give Flyway credentials through `QUARKUS_FLYWAY_USERNAME`/`QUARKUS_FLYWAY_PASSWORD`; do not grant runtime credentials schema ownership. Migrations are versioned, validated on every startup, and must be tested against a restored copy first. For an externally managed migration job, set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` on application replicas after migration completion.

## Backup, recovery, and retention

Enable encrypted physical base backups and WAL archiving for point-in-time recovery. For a logical backup use PostgreSQL tools with credentials provided through a protected `.pgpass` or secret mount:

```sh
pg_dump --format=custom --no-owner --file=payments.dump "$DATABASE_URL"
pg_restore --no-owner --dbname="$RESTORE_DATABASE_URL" payments.dump
```

Keep backups and WAL in a different failure domain, with restricted access and integrity verification. Daily backup, continuously archived WAL, and quarterly restore drills are initial operating policy; infrastructure owners must set and measure business-approved RPO/RTO. In a restore drill, recover to an isolated instance, run Flyway validation and reconciliation, verify sample receipts/ledger/balance totals, and measure elapsed recovery and data-loss windows. Do not connect a restored test database to live callers. Restart stateless services only after confirming the selected recovery point.

Payments and ledger entries are retained online indefinitely by default and never deleted through ordinary flows. `AUDIT_RETENTION_DAYS` configures the minimum archival age (default 2555 days); regulated retention/legal hold may require longer and always takes precedence. Archive immutable records only using a reviewed offline job that preserves IDs, hashes, opening baselines and reconciliation lineage. Do not prune ledger history without replacing the opening baseline through a separately reviewed migration and retaining verifiable archived history. Operational structured logs default to 90 days in the external logging platform (configurable there), financial records to the policy above. No raw payload/token/secrets should enter logs. Redact operational account identifiers in exported reconciliation reports as needed for recipients.

Rate windows contain principal identifiers and are not financial audit history. An administrative daily task may remove stale windows (runtime is not granted DELETE):

```sql
DELETE FROM rate_windows WHERE window_start < floor(extract(epoch FROM now())/60)-1440;
```

## Incidents and uncertain outcomes

- A timeout/503/500 may leave the client uncertain whether commit succeeded. Query `GET /api/v1/payments/{id}` or retry the identical canonical payload using the **same** UUID. Never generate a replacement UUID automatically. PostgreSQL uniqueness protects duplicate effects across replicas/restarts.
- A definitive stored rejection is immutable; replay returns the original status/code. Corrected instructions require a new UUID. ID conflict means an existing logical payment differs; inspect that payment before proceeding.
- Database failures cannot leave a partial transfer: account updates, ledger and finalized outcome share one transaction. There are two bounded transient retries by default; lock timeout, statement timeout, deadlocks and serialization errors return retryable 503 after exhaustion. Connection/commit failures are not recorded as business rejections.
- Investigate high `payments.retries`, `payments.lock.timeouts`, `payments.unexpected.failures`, and processing p95. Check database slow statements, lock owners, pool saturation, and replica rate windows. Avoid exposing query text or balances to callers.
- Readiness `/q/health/ready` checks Agroal PostgreSQL connectivity. Liveness `/q/health/live` checks process health. Restrict `/q/metrics` and `/q/openapi` to monitoring/engineering networks.
- On suspected compromise, revoke the affected JWT/issuer keys and account grant, rotate credentials, preserve immutable audit evidence, and reconcile before reopening admission.

## Observability and audit

Structured JSON logs carry MDC `correlationId` and valid `paymentId`, outcome/error code, HTTP status and latency. The durable payment records initiating subject, original correlation UUID and UTC timestamps. Telemetry runs after financial commit and catches telemetry failures. OpenTelemetry records request spans and a debit-stage event without payload/account values. Prometheus exposes HTTP counts, outcome counts by status/code, latency, replay count, transient retries, lock timeouts and unexpected failures. Alerts and trace/log retention are deployment configuration. Restrict error-code labels to the fixed taxonomy; never use payment IDs or arbitrary input as metric labels.

At the edge enforce connection limits, TLS 1.2+, request read/idle timeouts and upstream body limits consistent with the service. The per-subject shared PostgreSQL limiter defaults to 600 requests/minute; fixed windows permit a boundary burst and should be combined with ingress burst controls. Issuer keys must rotate through a trusted JWKS endpoint or controlled deployment. The service checks audience, issuer, signature, expiry, issue time and required scopes; no arbitrary request field grants ownership.
