# Financial operations runbook

## Reconciliation

Run at least daily and after incidents/backups/restores:

```sh
curl --fail-with-body http://localhost:18015/api/v1/admin/reconciliation -H "Authorization: Bearer $TOKEN"
```

The admin-only report uses a PostgreSQL REPEATABLE READ snapshot. `paymentAnomalies` detects completed payments without exactly one matching debit and credit, incorrect accounts/amounts or nonzero signed total, and ledger rows on rejected payments. `balanceAnomalies` compares each persisted available balance to its immutable opening balance plus all signed ledger entries. `healthy=false` requires investigation. The API never attempts repairs. Export and retain signed reports in restricted audit storage. Unit/integration tests deliberately corrupt an account fixture and assert detection.

Ledger entries and finalized payments are immutable by database triggers. Operational provisioning must establish the correct settled opening balance **once**, before transfers. Do not reset opening baselines, edit signed ledger rows or manually repair balances. Corrections require separately authorized compensating transfers with new IDs and an incident/audit reference. Payment account references are retained as snapshots, including nonexistent IDs on rejected instructions; customer, account, ledger and issue foreign keys use RESTRICT deletion policies.

## Retention

Financial payments, ledger, principals, correlation metadata and audit evidence are retained for an operator-configured minimum of **7 years by default**, subject to applicable local legal review and legal holds. Store the chosen retention period in deployment policy/secret configuration as `FINANCIAL_RETENTION_YEARS`; the service never physically deletes financial records or uses this as permission to purge. Any archival beyond that minimum requires a separately reviewed privileged procedure, legal approval, reconciliation before/after, backup and proof of complete paired records. Runtime roles cannot delete financial rows. Backups inherit retention and legal holds.

`ISSUE_RETENTION_DAYS` defaults to 90 in service deployment configuration and governs the separately scheduled restricted maintenance job below; do not grant this deletion privilege to runtime identities. Daily maintenance should bind the retention integer using the DB driver or psql variable, not concatenate uncontrolled SQL:

```sh
psql -v ON_ERROR_STOP=1 -v retention_days="${ISSUE_RETENTION_DAYS:-90}" -f scripts/retain-issues.sql
```

Financial rejection codes and original error receipts remain in `payments` after ancillary issue retention expires. Logs default to 30 days in the log platform, extended as required for active incidents; no access tokens, raw bodies, SQL parameters, balances or account identifiers belong in application logs. Secure transport/server logging must likewise redact credentials and bodies.

## Backup and restore drills

Use encrypted off-host storage, daily base backups and PostgreSQL WAL archiving for PITR. Define business-approved RPO/RTO and monitor archive lag, disk capacity and successful restore probes. Use a read-only backup identity and a chmod-600 `PGPASSFILE`; never put credentials on a command line or in git.

```sh
export PGHOST=database.internal PGPORT=5432 PGDATABASE=payments PGUSER=payments_backup
export PGPASSFILE=/run/secrets/backup.pgpass
./scripts/backup.sh /secure/encrypted-volume/payments.dump
pg_restore --list /secure/encrypted-volume/payments.dump
```

Quarterly and after major schema changes, restore into an isolated database with no public payment ingress:

```sh
createdb payments_restore
pg_restore --exit-on-error --no-owner --dbname=payments_restore /secure/encrypted-volume/payments.dump
```

Restore WAL to the approved point if using PITR, start the same application version with migrations disabled first, run reconciliation, inspect authorized receipts/balances and verify all finalized records and audit links. Apply any necessary new migrations only after validation. Record elapsed restore time, recovery point, reconciled counts and drill owner. A restore drill is an operational task; CI does not claim to verify production backup infrastructure.

## Incident handling

On unknown commit outcome (timeout, broken response or 503), preserve `paymentId` and correlation ID and query/retry the original instruction with the same ID. Never auto-generate another ID. Original completed receipts survive restart and support recovery without another debit. Rejected instructions require a new ID only when the business fields change.

On anomalous reconciliation, stop transfer admission at the gateway while keeping authorized query/health available. Preserve database snapshot, WAL and restricted logs. Escalate to financial operations, identify affected IDs with parameterized SQL, compare immutable receipts/ledger and opening balances, then plan an audited compensating action. Do not disable triggers in production. On DB outage readiness fails and transaction errors return 503; no financial state is kept in app memory. Review lock waits and DB saturation before increasing pool/retry values. Validate issuer/JWKS availability and TLS certificates for authentication incidents.

Connection pool sizing is per replica; cap the total against DB connection budget. A 600/minute in-process principal limiter protects each replica; enforce deployment-wide and unauthenticated-IP limits at the TLS gateway. Tune allowed ingress size/connection/read timeouts and JWT scope assignment there too. Keep backup, schema-owner and reconciliation privileges isolated from public service credentials.

## Reference performance measurement

The initial target is p95 <500 ms at 100 rps on representative hardware, excluding external network latency. `scripts/load.py` schedules requests at a fixed rate and reports p95, status counts and pass/fail; it does not hide server errors. Use a dedicated staging database and fresh fixtures. Explicitly authorize Alice to debit Bob's **test** account for alternating directions, and disable daily limits or set a suitable test policy. Increase the per-principal rate limit to at least 12000/minute before starting the app:

```sql
INSERT INTO account_debit_authorizations(account_id,subject) VALUES('ACC-2002','dev-alice') ON CONFLICT DO NOTHING;
```

```sh
RATE_LIMIT_PER_MINUTE=12000 docker compose up -d --no-deps app
export TOKEN="$(./scripts/token.sh)"
RATE=100 SECONDS=30 python3 scripts/load.py
```

Capture CPU/RAM, JVM, PostgreSQL and pool settings, number of replicas, test placement, p95 and error rates. Reconcile afterward. These results are environment-specific and are not a production performance guarantee. Test fresh funded fixtures; repeated same-ID requests measure replay rather than financial transfer capacity. Never run this driver against production accounts.
