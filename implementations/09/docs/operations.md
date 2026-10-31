# Operations

## Reconciliation

`GET /api/v1/admin/reconciliation` requires a bearer token with `payments:admin`. The report runs in a read-only REPEATABLE READ transaction, so concurrent transfers cannot create false discrepancies. It reports completed payment IDs whose ledger entry counts, signs, account links or exact amount disagree, and account IDs whose available balances differ from opening balance plus signed ledger sums. `balanced=true` means neither query found discrepancies. Opening balances are established when an account is provisioned and must never be changed to hide a discrepancy.

Schedule the report in the deployment scheduler at least daily and after recovery. Alert on any discrepancy; halt new submissions at ingress, preserve evidence and take a database snapshot. Investigate by payment ID and correlation ID. Do not edit immutable financial records or blindly retry using a new ID. Repair requires an independently reviewed compensating payment or controlled migration, followed by a successful reconciliation and audit record. Monitoring must also alert on elevated rejection rates, 503s, readiness failures, lock timeouts, processing latency, and missing scrape targets.

## Database roles and backups

Use a migration role with schema ownership and a separate runtime role. Example grants (execute as schema owner after migrations):

```sql
CREATE ROLE payment_runtime LOGIN;
GRANT CONNECT ON DATABASE payments TO payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_permissions,payments,ledger_entries TO payment_runtime;
GRANT INSERT,UPDATE ON payments TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT INSERT ON ledger_entries TO payment_runtime;
GRANT USAGE,SELECT ON SEQUENCE ledger_entries_id_seq TO payment_runtime;
```

Set passwords through a secret manager, not checked-in SQL. Provision customers/accounts with a separately controlled operations identity. Do not grant runtime DDL, TRUNCATE, deletion or trigger disable privileges. Keep `spring.flyway.enabled=false` for the least-privilege runtime after a deployment migration job has applied migrations. Limit PostgreSQL connections and network access; encrypt connections with `sslmode=verify-full` and verified certificates in `DB_URL`.

Enable managed PostgreSQL automated backups, encrypted storage and WAL archiving for point-in-time recovery. Set recovery objectives with the operator; exercise restoration in isolation at least quarterly. Example logical drill:

```sh
pg_dump --format=custom --file=payments.dump "$BACKUP_DB_URL"
createdb "$RESTORE_DB_URL"
pg_restore --no-owner --dbname="$RESTORE_DB_URL" payments.dump
```

Supply secrets via environment/secret store and restrict dump permissions. Verify row counts, migration history, ledger sums, opening balance reconciliation and original-ID replay after restoring. Never run a restore into the live database without a reviewed incident plan.

## Retention and audit

`RETENTION_DAYS` configures the documented minimum financial retention (default 2555 days, seven years); jurisdiction or contractual holds can increase it. Payments and ledger entries remain indefinitely in the live database until an operator-approved archive is implemented and verified; the application exposes no delete API. Backups and archives must retain original initiator, instruction, outcome, ledger links and timestamps for at least the configured period. Do not interpret this default as legal advice; organizational policy supplies the binding minimum. Audit logs use generated correlation IDs and valid payment IDs without payloads, tokens or sensitive balances. Configure `LOGGING_FILE_NAME` for rolling files and `AUDIT_LOG_HISTORY` for local daily file history, or centralize JSON stdout with a retention policy meeting the same audit requirements. Limit access to audit and financial data.

## Incidents and commit uncertainty

On database outage, readiness fails and transfers return 503 safely. Keep the original payment ID. Query status; if unknown, resubmit the exact canonical payload using that ID. A process failure after commit does not lose the payment; a duplicate returns the stored receipt. Investigate lock-timeout counters before raising timeouts. A confirmed security incident requires token revocation at the issuer, preservation of audit evidence, and scope/ownership review. Logs must not contain tokens, raw requests, SQL or stack traces from API errors. Restrict access to infrastructure-level logs and database diagnostics.

## Deployments

Build with `mvn verify`, then `docker build -t payment-service:1.0.0 .`. Run as non-root, use read-only root filesystem and writable temporary storage as needed, resource limits, startup/readiness/liveness probes and graceful termination. Manage secrets externally, require TLS, configure the production issuer/JWKS/audience, and never enable `dev`. Use a shared ingress rate limiter across replicas; application fallback counters are local per replica. OpenTelemetry trace context propagates via the framework; export through a deployment observability configuration if required. Prometheus exports request counts/latency, outcome codes, processing latency, replays, retries, lock timeouts and unexpected failures.
