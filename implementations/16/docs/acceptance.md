# Acceptance evidence map

The authoritative acceptance criteria are in `payment-processing-requirements.md`. Run `mvn verify`; append `-Dpayments.load=true` for the timed HTTP reference load. Tests use PostgreSQL rather than H2.

| Criterion | Automated evidence |
| --- | --- |
| 1 JSON accounting | `transferReplayConflictAndReconcile`, `jsonApiSecurityAndNegotiation` |
| 2 XML accounting | `xmlAndCrossFormatReplay` |
| 3 Malformed documents | `rejectsDuplicateJson`, `rejectsUnknownAndWrongTypes`, `inaccessibleLookupsAndMalformedDocumentsAreSafe` |
| 4 Field validation | `canonicalDecimalAndTrim`, `rejectsExcessPrecision`, `collectsValidationIssues` and strict parser validation |
| 5 Insufficient funds | `durableBusinessRejection` |
| 6 Invalid/inactive account | `unauthorizedAndFrozen`, hidden lookup checks, transactional account checks |
| 7 Authorization | `actualBearerSignatureIssuerAudienceExpiryAndScopes`, `unauthorizedAndFrozen`, inaccessible lookup checks |
| 8 Idempotent replay | `transferReplayConflictAndReconcile`, `xmlAndCrossFormatReplay` |
| 9 Changed payload | `transferReplayConflictAndReconcile` |
| 10 Race safety | `concurrentDuplicatesAndNoOverdraft`, `oppositeDirectionLocksAndAccountRestrictions` |
| 11 Atomic rollback | `injectedDebitFailureRollsBack`, deferred ledger constraints |
| 12 Lost response | Original outcome ignored, then replayed in `ledgerImmutableAndFreshServiceSeesCommit` and restart tests |
| 13 Restart durability | `survivesRealJvmProcessRestart` starts, stops and restarts independent JVMs against the same PostgreSQL |
| 14 Media negotiation | `jsonApiSecurityAndNegotiation`, `xmlAndCrossFormatReplay` |
| 15 XML security | `rejectsDtdAndDuplicateXml`, HTTP malicious XML checks |
| 16 Auditability | Immutable `requested_by` and timestamps, linked signed ledger entries, structured correlation logs |
| 17 Reconciliation | `transferReplayConflictAndReconcile` corrupts the persisted balance and verifies detection |
| Bounded waits and failure | `boundedLockTimeoutRollsBackRegistration`, `unavailableDatabaseIsTransient` |
| TLS/body/rate limits | `SecurityBoundaryTest` |
| Performance target | `referenceHttpLoadAt100RequestsPerSecond` when explicitly enabled |

Production secret management, TLS certificate provisioning, aggregate gateway limits, backup storage, legal retention approval and restore drill execution belong to deployment operations and are documented in `operations.md`; local fixture credentials are never production identities.
