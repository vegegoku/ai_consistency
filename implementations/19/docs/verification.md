# Verification record

Implementation folder: `implementations/19`. Final verification on 2026-10-28 used Temurin Java 21.0.5, Maven 3.9.9, Docker 29.9.2, and disposable PostgreSQL 16 containers.

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH mvn verify
```

Result: **BUILD SUCCESS**, 26 tests, zero failures, zero errors, zero skipped. Build evidence is `build.log`; JUnit XML and text reports are under `target/surefire-reports/`. The executable artifact is `target/payment-service-1.0.0.jar`.

| Acceptance area | Evidence |
|---|---|
| JSON and XML success, exact three-digit amounts | MessageParserTest and PaymentIntegrationTest.xmlJsonAndNegotiation / moneyAndReplayAndConflict |
| Malformed, duplicate, unknown or wrongly typed fields; XXE/DTD | MessageParserTest; PaymentIntegrationTest.missingScopeAndBodyLimitAndXmlAttack |
| Aggregated input errors, precision, zero/negative amounts, time windows, equal accounts | MessageParserTest.aggregateInvalidFields / precisionAmountsDatesAndSameAccountHaveDocumentedIssues |
| Insufficient funds and durable rejected replay | PaymentIntegrationTest.rejectionIsDurable |
| Missing/frozen/restricted accounts, authorization and concealed lookup | PaymentIntegrationTest.frozenAndLimitAndMissingDestination / permissionAndHiddenLookups / authorizedDelegateAndReadiness |
| Signature, issuer, audience, expiration and scopes | JwtSecurityTest uses a real RSA signer and loopback JWKS; API scope checks use Spring Security test JWTs |
| Same canonical JSON/XML replay and different-payload conflict | PaymentIntegrationTest.moneyAndReplayAndConflict / xmlJsonAndNegotiation |
| Concurrent duplicate IDs and competing withdrawals | PaymentIntegrationTest.duplicatesAndConcurrentSpending |
| Rollback after debit and after attempted ledger insertion | PaymentIntegrationTest.rollbackAfterDebitAndReceiptSurvivesNewService / ledgerInsertionFailureRollsBackBothBalancesAndPayment |
| Bounded lock waits, retries, 503 and no infrastructure rejection row | PaymentIntegrationTest.boundedLockTimeoutDoesNotRecordInfrastructureRejection |
| Lost-result recovery and restart durability | RestartDurabilityTest closes the application and pool, reopens against the same PostgreSQL database, retrieves and replays the exact original receipt |
| 415/406, default JSON and negotiated XML | PaymentIntegrationTest.xmlJsonAndNegotiation |
| Audit trail and immutable balanced ledger | Database triggers, persisted requested_by/correlation_id, PaymentIntegrationTest.corruptionDetectedAndLedgerImmutable |
| Reconciliation detects corrupted balance fixture | PaymentIntegrationTest.corruptionDetectedAndLedgerImmutable |
| TLS, correlation handling and rate limiting | RequestProtectionTest |
| 500 vs transient 503 and no SQL leakage | ErrorContractTest |

`docker compose config --quiet`, Python harness compilation, YAML/JSON syntax parsing, and checking all required OpenAPI submission responses also passed.

A separate `python3 scripts/measure_local.py` run sent 6,000 real HTTP payment requests over 60 seconds at 100.00 requests/second with real JWT verification, zero errors and p95 23.74 ms. Including warmup, the final database contained 6,100 completed payments and 12,200 ledger rows, with total funds 600.000 JOD and signed ledger sum 0.000. See `performance-result.json` and the operations runbook for reference-environment details and reproduction.

Production OIDC provisioning, certificates, gateway-wide quotas, jurisdiction-specific archive retention, encrypted backups/restore drills, and production-hardware load qualification are deployment/operational responsibilities documented in the runbook. The local Keycloak Compose configuration and container Dockerfile were delivered and syntax-checked; the Keycloak login walkthrough was not run during this verification. The application itself was started as an executable JAR for the measured HTTP run.
