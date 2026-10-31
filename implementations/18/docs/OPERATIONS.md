# Payment operations

## Deployment and privileges

Use PostgreSQL 16 with durable volumes, WAL archiving, synchronous commit enabled, monitored replication and a tested recovery plan. The application is stateless; rate-limit buckets and all payment results live in PostgreSQL, shared by replicas. Keep database network access private. Provide DB credentials, TLS private keys and JWT trust configuration using your platform's secret manager. Rotate credentials and issuer signing keys through JWKS. Never use the local Compose password in production.

Run Flyway with a dedicated migration owner before rolling out replicas. Set `QUARKUS_FLYWAY_MIGRATE_AT_START=false` on runtime replicas and provision a restricted runtime role using `ops/runtime-grants.sql`. Runtime grants omit ledger update/delete, payment identity updates, customer creation and account policy changes. The migration uses restrictive foreign-key deletion policies and immutable-payment/ledger/audit triggers. Completed transfers have deferred transaction-time checks for exactly one debit and credit, matching accounts and amount, with a zero sum. There is no ordinary delete endpoint.

Keep TLS end to end. Expose health and Prometheus only to platform monitoring. `/api/v1/admin/reconciliation` requires a bearer token with `payments:admin`. Use a separate operator identity; customer tokens should never receive administrative scope. Only enable `payments.trust-correlation-header=true` behind an ingress that replaces client correlation headers; by default the service generates fresh UUID correlation IDs.

## Reconciliation

Run daily and after incidents:

```bash
curl --fail https://payments.example/api/v1/admin/reconciliation \
  -H "Authorization: Bearer $OPERATOR_TOKEN"
```

`clean=true` means every completed payment has two balanced ledger entries with matching debit/credit account and amount, and every balance equals its documented immutable opening balance plus the signed ledger sum. The report uses a repeatable-read snapshot so concurrent transfers cannot produce false mismatches. `invalidPayments` and `balanceMismatches` identify records for a privileged investigator. Opening balances must be established during controlled account provisioning before any transfer and retained as accounting baseline; ordinary runtime credentials cannot change them.

Do not repair an account by editing ledger history or overwriting a balance. Freeze affected accounts with a privileged, audited procedure, preserve evidence, find the underlying error, and create approved compensating transfers/new correction records with their own IDs. Escalate clean-report failures immediately.

## Backup and restore drills

Use encrypted nightly full backups and continuous WAL archiving for point-in-time recovery. Protect backup keys separately. Example development backup (never put passwords on the command line):

```bash
docker compose exec -T postgres pg_dump -U payments -d payments -Fc > payments-backup.dump
```

In a segregated recovery environment, provision an empty PostgreSQL instance, restore roles through the secret manager, and restore with `pg_restore --no-owner --dbname=payments_recovery payments-backup.dump`. Apply only migrations newer than the restored Flyway history. Start one isolated application replica against the recovered DB, run readiness, fetch known completed and rejected receipts, verify balances, and run reconciliation. Test a duplicate retry to prove it does not debit again. Record the recovery point, recovery time, checksums and reconciliation report. Perform this drill quarterly and after backup/tooling changes. Never run a destructive restore over the primary without an approved incident procedure.

## Incident handling and uncertain outcomes

On timeout, connection loss or 503, keep the original `paymentId`; GET status or replay the same canonical instruction. Never assign a new ID just to bypass uncertainty. The unique registration waits for concurrent winners under bounded lock timeouts. Only known aborted deadlock/serialization/lock-timeout transactions receive bounded internal retries. Connection/commit uncertainty returns 503 and relies on the stable ID for safe resolution. Business rejections persist with no funds movement; infrastructure exceptions roll back and are never saved as a rejection.

For database outages, readiness fails and transfers fail safely; restore DB health before routing traffic. On high lock timeout/retry metrics, inspect PostgreSQL lock wait graphs, pool saturation and slow queries without logging user payloads. Source/destination rows are always locked in ascending identifier order; do not introduce an alternate transfer path with different lock ordering.

Monitor request counts, `payments_outcomes_total`, `payments_replays_total`, `payments_failures_total` (RETRY, LOCK_TIMEOUT, DATABASE_ERROR and API error codes), `payments_processing_seconds`, pool metrics, readiness and OpenTelemetry spans. Logs use structured JSON with correlation/payment IDs, outcome/code and latency. They never include tokens, raw payloads, balances, account identifiers or SQL exceptions. The durable payment record contains initiating principal and correlation ID; `payment_issues` retains business-rejection audit records. Export operational logs to access-controlled centralized storage.

## Retention

`AUDIT_RETENTION_DAYS` records the configured minimum retention period (default 2555 days / seven years). It is an operational policy setting, not an automatic deletion switch. Configure centralized operational-log retention to at least that period where applicable; adjust to legal requirements and jurisdiction before launch. Financial payments, ledger, opening baselines and rejection audit records are retained indefinitely by the application. Archive them using controlled encrypted DBA exports only after the configured minimum, legal holds and accounting signoff. Ordinary runtime accounts cannot delete them, and the application contains no purge job. Keep all records needed for the opening-balance reconciliation when archiving; losing a baseline invalidates reconciliation. Rate buckets contain only one current counter per principal and are overwritten each minute; inactive principal counters can be removed by a restricted maintenance job according to the identity-retention policy.

## Performance qualification

`ops/load-test.js` sends 100 new valid 0.001 JOD instructions per second for 60 seconds and checks completion rate and p95 <500ms. Run with k6 and an authorized token against representative production-like CPU, memory, DB latency, TLS, JWT and replica settings. Ensure account policy/daily limits and funds allow the entire run. Record hardware, JVM/DB versions, pool sizes, p95/p99, errors and reconciliation before/after. Passing functional tests is not evidence of the performance target. A local baseline is reported separately in `docs/VERIFICATION.md`; qualify deployment hardware before initial release.
