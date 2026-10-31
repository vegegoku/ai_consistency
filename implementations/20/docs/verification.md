# Verification evidence

Run on 2026-10-28 in this isolated implementation directory, Linux x86_64, 16 logical CPUs, approximately 61 GiB RAM, Temurin Java 21.0.5, Maven 3.9.9, Docker 29.9.2 and PostgreSQL 14 containers.

## Maven acceptance suite

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH mvn verify
```

Result: **BUILD SUCCESS; 28 tests, zero failures, zero errors, zero skipped**. The packaged artifact is `target/quarkus-app/quarkus-run.jar` with its adjacent libraries. Surefire evidence is in `target/surefire-reports`. This exact `mvn verify` command is configured in CI using a Java 21 installation.

| Acceptance criterion | Evidence |
| --- | --- |
| 1 JSON success | `jsonSuccessAndCrossFormatReplay`: 500/100 become 374.250/225.750 |
| 2 XML success | `xmlSuccessAndXmlResponse`: same balances, canonical XML amount |
| 3 Invalid structure | malformed documents, duplicate keys/elements, invalid typed values |
| 4 Invalid fields | amount scale/range, missing fields, date and same-account issue aggregation |
| 5 Insufficient funds | durable original rejection and unchanged balances |
| 6 Invalid accounts | missing and frozen accounts safely rejected |
| 7 Authorization | unrelated debit/view/payment access denied; real JWT signature/issuer/audience/expiry/scope tests |
| 8 Replay | same command and equivalent JSON/XML return original receipt |
| 9 Payload conflict | amount change returns idempotency conflict without a second transfer |
| 10 Concurrency | 12 identical requests transfer once; 12 competing withdrawals cannot overdraw; 20 opposing transfers finish without deadlock |
| 11 Atomic rollback | injected failure immediately after debit rolls back payment and all financial state |
| 12 Failure uncertainty | stored receipt/replay equality, including retry after database outage/recovery |
| 13 Restart durability | separate packaged process is stopped and restarted against the same persisted PostgreSQL state |
| 14 Media negotiation | JSON/XML, unsupported request/response formats, canonical errors |
| 15 XML security | DTD/XXE, duplicate and nested XML rejection |
| 16 Auditability | immutable linked ledger/payment rows, initiating subject and correlation fields, structured response logs |
| 17 Reconciliation | deliberately missing ledger row and corrupted account balance both detected |

Additional checks cover a two-second lock timeout without a durable rejection, immutable financial rows, shared rate limiting and standard oversized-body errors.

## Packaged HTTPS and fault/recovery acceptance check

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem python3 scripts/verify-packaged.py
```

Result: **PASS**. The script provisions an isolated database/container, runs the packaged service with HTTPS and real signed development JWTs, transfers 125.750 JOD, performs the reference workload, terminates/restarts the JVM, verifies the original receipt/replay and persisted balance, stops PostgreSQL, verifies readiness/submission 503 responses, restores PostgreSQL and verifies exactly-once recovery and clean reconciliation. Containers and processes are cleaned up automatically. The command is also included in CI after Maven verification.

Measured reference workload:

- 1,000 successful transfers over 9.996 seconds; achieved 100.04 requests/second.
- All responses: HTTP 201; no errors.
- p50: 6.774 ms; p95: 10.408 ms; maximum: 24.363 ms.
- Source balance after original payment and load: 373.250 JOD; restart and outage/recovery did not change it.
- Application restart, same-ID replay, database outage, recovery and reconciliation: PASS.

Machine-readable results are in `target/packaged-verification.json`; this document preserves the measurements if build outputs are cleaned. `scripts/load-test.js` provides a longer 60-second k6 workload for reference-environment capacity studies. The short local result is evidence for this machine, not a production performance guarantee.

## Deployment responsibilities

Production issuer/JWKS, managed TLS certificates, least-privilege runtime/migration database roles, encrypted backups/WAL archival, external reconciliation scheduling, platform log retention and periodic restore drills are deployment configuration and documented in `operations.md`. Local development credentials and signing keys must never be used in production. No GitHub-hosted workflow run or production restore drill was available in this isolated delivery; the configured commands were executed successfully here.
