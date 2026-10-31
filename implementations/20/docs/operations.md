# Operational runbook

## Reconciliation

Use a token with `admin` group and `payments:admin` scope to `GET /api/v1/admin/reconciliation`. A healthy response is `{"issues":[]}`. Schedule this endpoint from an authenticated operations job after settlement and alert on every discrepancy. It reports completed payments without exactly two ledger rows or a zero signed sum, and accounts whose balance differs from `opening_balance + sum(ledger signed_amount)`. Opening balances are immutable administrative baselines: establish them only during account onboarding with a documented signed migration. Treat any later change as a financial correction requiring independent approval outside this service.

For investigations use a read-only database role:

```sql
SELECT p.payment_id,count(l.id),sum(l.signed_amount)
FROM payments p LEFT JOIN ledger_entries l USING(payment_id)
WHERE p.status='COMPLETED' GROUP BY p.payment_id
HAVING count(l.id)<>2 OR coalesce(sum(l.signed_amount),0)<>0;
SELECT a.id,a.available_balance,a.opening_balance+coalesce(sum(l.signed_amount),0) AS derived_balance
FROM accounts a LEFT JOIN ledger_entries l ON l.account_id=a.id
GROUP BY a.id HAVING a.available_balance<>a.opening_balance+coalesce(sum(l.signed_amount),0);
```

Never repair balances with ad hoc SQL. Freeze affected accounts, preserve evidence, identify the original instruction, and post an approved compensating transfer with a new UUID if appropriate. The ordinary API has no financial deletion or editing endpoint.

## Backup and restore

Use encrypted managed PostgreSQL backups with WAL archiving and point-in-time recovery. Set deployment RPO/RTO and monitor actual replication/archive lag; an initial operational target is RPO 5 minutes and RTO 1 hour, validated in drills rather than assumed. Store credentials in a secrets manager and apply rotation. Take a backup before every schema deployment.

Local demonstration (protect the resulting dump as financial data):

```sh
docker compose exec -T postgres pg_dump -U payments -d payments -Fc > payments.dump
# Restore into an isolated database, never over the only live database.
docker compose exec -T postgres createdb -U payments payments_restore
docker compose exec -T postgres pg_restore -U payments -d payments_restore --no-owner < payments.dump
```

Quarterly restore drills: restore backup plus WAL to isolated infrastructure; check Flyway schema version, row counts, latest timestamps and reconciliation; test one controlled authorized balance query; record measured recovery time and recovered transaction boundary. Do not disable immutability triggers during normal operations.

## Retention

`PAYMENT_RETENTION_DAYS` records the configurable default review horizon (2555 days). Deployment policy must match applicable legal/accounting obligations; the service never autonomously deletes financial data. Keep payments, ledger rows, initiating principal and correlation IDs together for the required horizon. Export encrypted signed archives before any separately approved archival migration. Extend retention for disputes, investigations or legal holds. Configure platform log retention separately (suggested 30 days searchable, 90 days encrypted archive); logs exclude tokens/payloads. Delete expired request-rate buckets daily using `DELETE FROM request_rates WHERE minute < extract(epoch from now())::bigint/60 - 120;` using a maintenance role; these buckets are non-financial.

## Incident response

For a timeout after submission, query `/api/v1/payments/{paymentId}` with the initiating identity. If completed, use that stored receipt. If rejected, the rejection is definitive and corrected instructions need a new UUID. If absent or database unavailable, repeat the exact payload with the **same** UUID. Never infer financial outcome from missing telemetry or a dropped HTTP response.

If database connectivity fails, readiness fails; remove replicas from traffic and restore connectivity. No in-memory balances or offline transfer queues exist. Lock timeouts return retryable 503; inspect `pg_stat_activity` / `pg_locks` and long-running transactions before increasing timeouts. Scale stateless replicas with shared PostgreSQL rate buckets, pool capacity planning, and bounded ingress concurrency. Restrict health/metrics access at the network layer. Redact SQL parameters from infrastructure query logging.

If reconciliation fails, freeze affected accounts, stop new debits when systemic, preserve database snapshots and correlation IDs, escalate to finance/security, and determine whether approved compensation is needed. Do not alter ledger rows or final payment records. Rotate JWT/DB secrets on suspected compromise; redeploy with the trusted issuer/JWKS configuration, audit initiating principals, and reconcile before reopening traffic.

## Performance validation

`scripts/load-test.js` is a k6 constant-arrival test targeting 100 requests/second for 60 seconds with p95 below 500 ms. Run on an isolated reference deployment with `PAYMENT_RATE_PER_MINUTE=12000`, sufficient account funds, representative TLS/network/database storage, and a local bearer token:

```sh
TOKEN=$(python3 scripts/dev-token.py alice) k6 run scripts/load-test.js
```

Record hardware, PostgreSQL/app resource limits, achieved throughput, error percentage, queueing and p95; repeat across replicas and compare reconciliation before/after. These figures are engineering targets, not guarantees. Production capacity requires representative measurements.
