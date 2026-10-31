# Verification record

Run from the project root with JDK 21 and a working Docker daemon:

```sh
mvn -B verify
```

The suite contains 28 tests: 6 domain/parser tests, 11 HTTP/security tests, 10 PostgreSQL transaction tests, and 1 packaged-process durability test. PostgreSQL 16 containers are isolated and mandatory. The Failsafe integration test launches the actual packaged JAR, forcibly terminates its JVM, restarts it against the same database, then stops PostgreSQL to verify readiness and safe 503 behavior.

| Acceptance criterion | Evidence |
| --- | --- |
| JSON accounting | `jsonSuccessAndQueryAreCanonical`, `exactAccountingReplayConflictAndDurability` |
| XML accounting | `xmlSuccessAndResponse`, `xmlSharesCanonicalCommand` |
| Malformed documents | `duplicateUnknownAndTypedPropertiesAreRejected`, HTTP malformed tests |
| Invalid fields | `excessivePrecisionAndMissingFieldsCollectIssues`, `invalidAmountDateAndSameAccountAreRejected` |
| Insufficient funds | `businessRejectionIsDurable` |
| Invalid/frozen accounts | `frozenAccountAndPolicyLimitReject`, ownership/non-enumeration tests |
| Authorization | `authenticationScopeAndOwnership`, `unauthorizedCallerCannotReadOrDebit` |
| Equivalent retry across formats | `crossFormatReplayHasOneFinancialEffect` |
| Changed payload conflict | `exactAccountingReplayConflictAndDurability` |
| Race safety | `concurrencyPreventsOverdraftAndDuplicateEffects`, `oppositeDirectionTransfersUseConsistentLocks` |
| Atomic rollback | `failureAfterDebitRollsBack`, `ledgerPersistenceFailureRollsBackBothBalanceUpdates` |
| Lost response and restart | `DurabilityIT.responseLossRestartAndDatabaseOutageAreSafe` |
| Media negotiation | `mediaNegotiationAndValidation` |
| XML parser security | `xmlRejectsDtdDuplicateNamespacesAndNestedElements`, HTTP DTD fixture |
| Auditability | Immutable SQL records plus structured request log fields; initiating JWT subject persisted in payments |
| Reconciliation corruption | `reconciliationDetectsCorruptionAndLedgerIsImmutable` |
| JWT issuer/audience/signature | `issuerAudienceAndSignatureAreVerified` |
| Request limits | `bodyLimitIsEnforced`, `chunkedBodyLimitUsesErrorContract`, `rateLimitIsSharedByServiceInstances` |

`docs/load-result.json` records the open-loop 100 requests/second measurement for 30 seconds (3,000 payments) against the packaged local service and PostgreSQL 16.15. Reference host: Intel i7-11700, 16 logical CPUs, 64 GiB RAM, Linux; Temurin 21.0.5; Docker 29.9.2. The final successful measurement recorded 3,000 requests, zero errors, and p95 5.278 ms. The test uses a warmed HTTP/1.1 service over loopback, reset development account balances, amount 0.001 JOD, default connection pool, and local tracing disabled. Other implementation work shared the host, so this is an engineering reference measurement rather than a dedicated capacity benchmark. The script exits nonzero on any non-201 response or p95 >= 500 ms.

Operational limits: production TLS/identity-provider integration, disaster-recovery drills, and log/archive lifecycle rules depend on deployment infrastructure. The repository documents and configures those controls; no production deployment or external backup system was provisioned by this implementation. HTTP/2 is intentionally disabled; HTTP/1.1 supports both required payload formats.
