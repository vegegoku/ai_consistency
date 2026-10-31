# Operations runbook

## Deployment and access

Run stateless application replicas with a single authoritative PostgreSQL primary. Require TLS at every exposed endpoint and encrypt database transport (`sslmode=verify-full`). Use managed secret injection for database, OIDC and TLS settings. Give migrations a dedicated schema-owner role; use `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` for startup migrations or run migrations once before rollout and set `SPRING_FLYWAY_ENABLED=false` on application replicas. `least-privilege.sql` grants only required runtime operations. The local Compose database owner is a development convenience and must not be used as the runtime production role.

Schema migrations are append-only and versioned. Rehearse them against a restored production-like database. Roll forward after deployment; never downgrade financial data blindly. Do not expose privileged account administration. Provision customers/accounts/grants through controlled DBA or back-office processes. Set opening_balance once to actual opening available funds; subsequent funds changes require paired immutable ledger entries through approved financial workflows.

Production ingress must enforce global caller rate limits across replicas, request-body limits and request read/idle deadlines. Keep database connection budgets below primary capacity. Alert on readiness failures, 503/500 increases, lock timeouts, elevated rejection rates and p95 latency. Enable OTel with an internal collector and redact authorization headers at every proxy. Restrict Prometheus and reconciliation using payments:admin. Keep operational JSON logs in access-controlled encrypted storage; the application never writes payloads or credentials.

## Reconciliation

Call `GET /api/v1/admin/reconciliation` with a payments:admin JWT. The report uses one repeatable-read snapshot to identify completed payments without exactly two zero-sum entries and account balances differing from `opening_balance + SUM(signed_amount)`. Normal reports have `healthy=true`, empty account/payment lists. Run at least daily and after deploys, outages and restores. Schedule this read-only API from your operator scheduler and retain the timestamped report; the application does not require a scheduler or distributed job coordinator.

Database deferred triggers additionally enforce matching debit/source and credit/destination amounts for each completed payment, and prohibit rejected records with ledger entries. Ledger rows cannot be updated or deleted through normal SQL roles. Never repair a mismatch by rewriting history. Quarantine affected transfer paths, preserve evidence, reconcile against customer instructions, and use a reviewed corrective financial transaction with a new identity.

## Retention

`RETENTION_DAYS` defaults to 2555 days (7 years) and defines the operator's minimum review horizon for payments, ledger and initiating-principal audit records. It is an operational policy input, not a deletion timer. There is no automatic purge or financial deletion endpoint. Apply jurisdiction-specific legal holds and longer requirements before archival. Keep payment IDs/fingerprints and receipts discoverable for the supported retry lifetime; archival must retain that index. Operational logs default to a 90-day external retention policy; configure your collector's retention and redaction for applicable law. Financial retention must never be shortened solely through the environment setting.

## Backup and restore

1. Enable encrypted daily base backups plus PostgreSQL WAL archiving for point-in-time recovery. Back up role definitions and OIDC configuration separately; secrets belong in the vault. Restrict restore permissions and cross-region storage access.
2. For a local logical backup: `docker compose exec -T db pg_dump -U payments -d payments -Fc > payments.dump`. Store backups securely outside source control.
3. Restore to a separate isolated database: `pg_restore --no-owner --dbname="$RESTORE_DATABASE_URL" payments.dump`. Restore role grants using deployment credentials. Never restore over a live financial primary while traffic runs.
4. Run Flyway validation, readiness checks and reconciliation, verify recent completed/rejected receipts and paired ledger amounts, then perform a controlled same-ID replay and ensure no second transfer.
5. Conduct quarterly restore drills, recording recovery point/time objectives, observed times and reconciliation evidence. Test loss of primary and rollback of in-flight transactions. Do not claim recovery guarantees from backup creation alone.

## Incidents

A completed transaction survives JVM failure. When a caller times out or sees 503, use GET with the original paymentId, then retry that same instruction and identity if needed. Never tell clients to retry with a new ID. A missing receipt can mean rollback or an in-flight transaction; an unchanged identity resolves the race safely. 409 insufficient-funds/account/limit rejections are definitive and retained; corrected business instructions require a new ID.

For database outages, stop accepting transfers through readiness routing and preserve the idempotency store. Do not use an alternate in-memory balance store. For lock contention, identify long-lived transactions and query plans using DBA tools, then resolve the holder rather than increasing unlimited retries. The service locks account IDs in deterministic order and retries only transient PostgreSQL concurrency conditions.

For suspected financial corruption, remove affected routes, preserve logs plus database/WAL snapshots, identify original correlation/payment IDs, run reconciliation, and escalate through the financial incident owner. Never log customer payloads during troubleshooting. A post-commit observability failure cannot undo funds; investigate it separately and discover the committed receipt by its original identity.

## Performance

Run `TOKEN=... python3 scripts/load.py --url https://... --source ACC-1001 --destination ACC-2002 --rate 100 --seconds 30`. The offered rate is fixed and scheduling delay is included in end-to-end latency, preventing coordinated omission. Ensure funding and daily policy permit all instructions and increase RATE_PER_MINUTE for the isolated benchmark. The script writes a JSON report and fails if any transfer fails or p95 is >=500 ms. Capture machine specifications, database topology and concurrent traffic with the report. Use a dedicated fixture; load tests move real funds within that fixture and must not run against unrelated customer accounts.
