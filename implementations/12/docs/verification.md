# Verification evidence

Verified on 2026-10-28, using Java 21.0.5 (Temurin), Maven 3.9.9, Docker 29.9.2, and PostgreSQL 16.15 (`postgres:16-alpine`). All work and output are confined to this implementation folder; local disposable database containers were removed after verification.

## Build and automated tests

Exact command used on this host (whose shell otherwise defaults to Java 17):

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH \
mvn -B verify
```

Result: **BUILD SUCCESS**, exit 0; 25 Surefire tests (20 PostgreSQL REST/integration tests and 5 parser/domain tests), plus 1 Failsafe packaged-service restart test. **26 tests, 0 failures, 0 errors, 0 skipped.** Reports are in `target/surefire-reports` and `target/failsafe-reports`; the build log is `verify.log`.

The rollback test intentionally throws after the source balance update; its expected INTERNAL_ERROR log verifies the failure path and is not a failing test. The lock-timeout test intentionally produces SQLState 55P03 and verifies 503 plus successful retry with the same ID. Test HTTP ports and PostgreSQL ports are random, so concurrent independent project builds do not conflict. Docker API 1.44 is explicitly selected for compatibility with the host daemon.

The packaged restart test starts the production jar in one JVM, commits a payment, terminates that JVM, starts another JVM against the same PostgreSQL instance, and confirms the exact original receipt, unchanged balances, replay status 200 and two balanced ledger entries.

## Acceptance coverage

| Criterion | Evidence |
|---|---|
| 1 JSON success | Exact 500.000/100.000 → 374.250/225.750, one payment and two ledger entries |
| 2 XML success | XML submission and equivalent JSON/XML replay share fingerprint and accounting |
| 3 Invalid structure | Malformed JSON, duplicates, unknown keys/elements, trailing data and invalid typed values |
| 4 Invalid fields | Collected issues for precision, amount, UUID, date, required text and same account |
| 5 Insufficient funds | Durable rejection, zero ledger entries, unchanged balances and original rejection replay |
| 6 Invalid accounts | Nonexistent source/destination, frozen/closed, receive-disabled and account-limit policies |
| 7 Authorization | Missing JWT, wrong issuer/audience/signature/expiry, scope failures, customer ownership and explicit delegated debit grants |
| 8 Idempotency | Repeated equivalent JSON/XML instruction preserves original processedAt and one effect |
| 9 Payload conflict | Same ID with a different amount returns IDEMPOTENCY_CONFLICT |
| 10 Concurrency | Twelve identical requests, twelve competing debits and twenty opposing transfers; exact counts and no overdraft |
| 11 Atomic rollback | Injected exception after debit rolls back balances, payment identity, and all ledger records |
| 12 Failure uncertainty | Original response discarded, same-ID retry returns committed result without another transfer |
| 13 Restart durability | Separate packaged-service JVMs discover the same committed receipt and financial state |
| 14 Negotiation | JSON/XML success and authentication errors, 415 unsupported request, 406 unsupported response |
| 15 XML security | DTD, external file/network entities, repeated elements, namespaces and nesting rejected before processing |
| 16 Auditability | Persisted initiating principal/correlation UUID/timestamps and immutable linked ledger; operational logging plus response correlation checks |
| 17 Reconciliation | Deliberately changed account balance detected; direct finalized-payment/ledger updates rejected; reconciliation mode does not leak into later pooled transfer transactions |

Additional checks cover fixed-window rate limiting, normal/chunked body size limits, unique untrusted-header correlation IDs, post-restart authorized balance lookup, lock timeout and retry, and opening-baseline reconciliation.

## Final packaged-service reference load

Command (after building):

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH \
python3 scripts/reference-benchmark.py
```

Final result: exit 0; **6,000/6,000 HTTP 201 completions over 60.003 seconds at a target 100 requests/second; p95 471.60 ms**, meeting the initial <500 ms target for this run. The final measured results are in `load-results.json`, and exact SQL totals are in `load-reconciliation.txt`. An earlier run on the same host measured p95 7.71 ms; this variation underlines why shared development hardware is not a production performance guarantee.

Reference environment: one application JVM, default 32-connection pool, one local PostgreSQL Docker container, loopback networking, Intel Core i7-11700 (8 cores/16 threads), 61 GiB system RAM, no CPU/memory reservations, a shared development host, OTLP export disabled, JSON operational logging enabled, and the per-principal limiter increased to 10,000/minute for the test. Every payment moves 0.001 JOD between the same hot account pair, exercising row-lock contention. No external network latency is included.

Final SQL totals: 6,000 completed payments totaling 6.000 JOD; 12,000 signed ledger entries totaling 0.000 JOD; balances 494.000/106.000 on the active pair, 100.000 each on frozen/closed fixtures; total 800.000 JOD, identical to the opening baseline. Every temporary reference container and service process is stopped by the runner.

## Artifact review and deployment responsibilities

The generated OpenAPI document parses as JSON. The packaged runtime application jar was inspected and contains neither the test private signing key nor test seed data. Python helper scripts compile successfully. The application deliberately uses HTTP/1.1 because this Quarkus version's HTTP/2 request initialization conflicts with early bounded body buffering; the supported server customizer disables H2C and TLS HTTP/2, with HTTP/2 terminated at ingress when needed.

No known required functional acceptance gaps remain in the tested implementation. Production issuer/JWKS provisioning, TLS certificate mounting, least-privilege DB role provisioning, ingress restrictions, external monitoring/log retention, encrypted backups/WAL storage, legal retention approval and actual restore drills are deployment/operational responsibilities documented in `operations.md`; they cannot be provisioned against an unspecified production environment here. The Dockerfile and Compose definition are delivered; the verified executable artifact is the Maven-packaged service. A production container image build and production-environment load/restore/TLS checks were not executed in this verification.
