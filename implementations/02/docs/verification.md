# Verification evidence

Verification date: 2026-10-28. Environment: OpenJDK 21.0.5, Maven 3.9.9, Quarkus 3.40.1, Docker Engine 29.9.2, PostgreSQL 16 Testcontainers, Ubuntu Linux. All work and artifacts are confined to implementation 02.

## Automated build

Command used:

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH mvn -B verify
```

Result: BUILD SUCCESS; Surefire ran 31 tests (23 authenticated API/PostgreSQL tests and 8 strict parser/validation tests), and Failsafe ran 1 packaged-process restart/outage test. All 32 passed, none failed or skipped. Reports are in `target/surefire-reports` and `target/failsafe-reports`. The expected fault-injection case emits one INTERNAL_ERROR log and verifies total rollback; it is a passing test. The CI workflow runs the same Maven verify lifecycle; no external CI run or deployment is claimed.

## Acceptance coverage

| Criterion | Verification |
|---|---|
| 1 JSON success | 125.750 debit/credit yields 374.250 and 225.750, one payment and two ledger rows |
| 2 XML success | Equivalent XML yields identical accounting and a canonical XML amount |
| 3 Invalid structure | Malformed, duplicate, unknown and invalid typed fields are rejected with no balances changed |
| 4 Invalid fields | Safe field errors are collected, including simultaneous maximum/date/missing-field issues |
| 5 Insufficient funds | Durable rejection and unchanged account balances; replay keeps original rejection |
| 6 Invalid accounts | Missing, frozen, closed and restricted destinations cannot transfer funds |
| 7 Authorization | OAuth JWT claims/scopes plus database ownership and explicit permissions; unrelated lookups return 404 |
| 8 Replay | Cross-format retries preserve the receipt and create only two ledger entries; historic retries remain valid |
| 9 Conflict | Changed amount under the same ID returns IDEMPOTENCY_CONFLICT |
| 10 Concurrency | Duplicate races, competing debits and opposing transfers preserve funds and avoid overdrafts |
| 11 Rollback | A CDI test alternative injects failure after debit; every balance/payment/ledger change rolls back |
| 12 Unknown response | Dropping the original service result and retrying returns the stored committed receipt |
| 13 Restart durability | Failsafe launches the actual packaged JVM twice against the same PostgreSQL instance and checks exact receipt, balances and ledger |
| 14 Negotiation | JSON/XML, 415 and 406; oversized bodies retain the standard error envelope |
| 15 XML security | DTD/entities/namespace/nesting rejected; a listening loopback HTTP server records zero entity-resolution requests |
| 16 Auditability | Stored initiating principal, correlation ID, timestamps, immutable linked debit/credit entries, structured API logging |
| 17 Reconciliation | Deliberately corrupted balances and missing ledger entries are reported |

Additional checks cover rate limiting and Retry-After, bounded lock waits without durable transient rejection, account transfer/daily limits, financial immutability, liveness/readiness/OpenAPI/metrics, real database outage and failed readiness, NUL validation, amount precision and exact canonical fingerprints.

## Load measurement

Run `JAVA_HOME=/path/to/jdk21 scripts/benchmark.sh`. It starts an isolated PostgreSQL container and packaged service, uses the published development-only keys, permits local HTTP only for this fixture, raises its test rate quota, and generates 100 requests/second for 60 seconds using `scripts/load_test.py`. It then reconciles the database and removes the fixture processes/container.

The final run completed all 6,000 requests with HTTP 201, with p95 **78.917 ms** and maximum 1,274.774 ms. Its result is archived in `load-report.json`; earlier measured runs are preserved in `load-runs.json`. On this shared host the earlier p95 measurements ranged from 5.232 ms to 423.696 ms, so contention materially affects timing. All those runs met the 500 ms p95 target and completed all 6,000 receipts. The workload uses 0.001 JOD per transfer from an initially funded 500.000 JOD source; 6,000 successful transfers produce 12,000 ledger entries with a signed sum of 0.000. The final reconciliation reported healthy, with 12,000 ledger entries and a signed sum of 0.000; the raw summaries are archived in `load-reconciliation.json` and `load-ledger.txt`. The local service uses four active JVM processors and a 256 MiB maximum heap; the database shares the host. Loopback HTTP and PEM verification are used, without TLS, external network latency or a remote issuer lookup. These are measured engineering results for this fixture, not a production capacity guarantee. Repeat with production TLS, hardware, topology, gateway quotas and realistic data.

## Review and deployment boundaries

Self-review checked the supplied requirements, transaction/idempotency boundaries, permission checks, immutable database constraints, transport error paths and operations guidance. No additional reviewer agent was introduced, preserving the requested agent isolation. Compose, CI and OpenAPI YAML parsed successfully; the benchmark shell passed `bash -n`, Python scripts compiled, and Docker Compose configuration validated. No incomplete implementation or placeholder was left.

Production issuer/JWKS, certificates, database secrets/least-privilege roles, gateway-wide quotas, backups/restore drills, and legally appropriate retention must be supplied by the deployment environment as documented in `operations.md`. Financial deletion is intentionally unavailable; retention is indefinite with a configurable documented minimum. Performance under a particular production topology and external CI execution remain deployment validations, not claims made by this delivery.
