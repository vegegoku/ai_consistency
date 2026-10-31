# Verification evidence

Verified on 2026-10-28 using full Temurin JDK 21.0.5, Maven 3.9.9, Docker 29.9.2 and PostgreSQL 16.15 containers. `hardware.txt` describes the shared Linux host. This environment's default Java is 17, so the exact successful command was:

```sh
env JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
 JAVACMD=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin/java mvn -q verify
```

Result: exit 0, 28 tests, zero failures/errors/skips: 7 parser tests, 19 PostgreSQL/application/API tests, 1 transport-security test and 1 packaged-process integration test. Reports remain under `target/surefire-reports` and `target/failsafe-reports`; CI uploads them. No database tests were bypassed.

The process integration test kills the first JVM forcibly, starts a fresh JVM against the same database, queries persisted balances, replays the original payment and checks the immutable signed ledger. It also offers 500 HTTP transfers at 100 requests/second for five seconds, asserting every response is 201 and p95 is below 500 ms. The last recorded run measured p95 **11.476 ms**, maximum **30.064 ms**, including scheduling delay; see `load-report.json`. This short local test establishes a measured engineering baseline, not representative production capacity. Repeat longer benchmarks on deployment hardware using `scripts/load.py`.

`docker build -t payment-service-17:verification .` completed with exit 0. The image runs as the unprivileged `payments` user. `DB_PASSWORD=... docker compose -p payment17 config -q` validated the local setup.

A separate live local smoke check started the provided Compose PostgreSQL and Keycloak realm and the packaged service, loaded `docs/dev-seed.sql`, obtained an Alice token from Keycloak, then verified initial payment 201, identical replay 200, 374.250 JOD source balance, and authorized admin reconciliation/Prometheus responses 200. The report was healthy. These smoke containers, their test volume and application process were stopped after verification.

## Acceptance coverage

| Requirement | Automated evidence |
| --- | --- |
| JSON success / exact balances | `jsonSuccessAndAuthorizedLookups` |
| XML success / format parity | `xmlSuccessAndCrossFormatReplay` |
| Invalid structure | `malformedPrecisionAndXxeHaveNoEffects`, parser duplicate/type tests |
| Invalid fields / aggregate issues | `collectsFieldIssues`, scale test, malformed API test |
| Insufficient funds | `insufficientFundsRejectionIsDurableAndReplayStable` |
| Frozen/nonexistent accounts | `authorizationFrozenAndMissingAccountsCannotMoveFunds` |
| Unauthorized debit / JWT checks | authorization test, `jwtSignatureIssuerAudienceExpirationEnforced` |
| Equivalent replay | XML/JSON replay, duplicate race, process restart |
| Changed payload conflict | `retriesOriginalReceiptAfterResponseLossAndConflicts` |
| Concurrent debits / duplicates | both `*Race*` / `concurrentDistinctPaymentsCannotOverdraw` tests |
| Rollback after debit | `rollbackAfterDebit` |
| Lost-response retry safety | original receipt replay tests and committed-process recovery |
| Full process restart durability | `ProcessDurabilityIT` |
| 415 / 406 / negotiated XML | `negotiationAndPayloadLimits`, XML response assertion |
| XXE / DTD / duplicate XML | parser security and API tests |
| Audit / ledger invariants | immutable-ledger, database-constraint and outcome lookup assertions |
| Corrupted fixture reconciliation | `ledgerIsImmutableAndReconciliationDetectsCorruption` |
| Bounded infrastructure waits | `lockTimeoutIsBoundedAndLeavesNoPermanentRejection` |
| TLS / untrusted forwarding | `TransportSecurityTest` |
| Rate limit / telemetry isolation | `rateLimitDoesNotMoveMoney`, `postCommitMetricsFailureCannotReplaceReceipt` |

The deployment operator must still provide production OIDC registration, certificates, managed secrets, least-privilege roles, shared ingress rate limits, backup/WAL retention and actual restore drills as documented in the operations runbook. Development fixtures are deliberately isolated from production migrations.
