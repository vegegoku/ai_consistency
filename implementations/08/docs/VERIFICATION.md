# Verification coverage

The test command is `mvn -B verify` under Java 21 with Docker. Tests use real PostgreSQL 16, never H2 or mocked balance repositories.

| Requirement | Automated evidence |
|---|---|
| Exact transfer and balanced ledger | PaymentServiceTest.transfersExactlyAndReplaysOriginalAfterRestart |
| JSON/XML and equivalent retries | PaymentApiTest.jsonSuccessLookupAndBalance, xmlAndJsonAreCanonicalEquivalent |
| Strict fields, type/precision, duplicate/unknown keys, safe normalization | MessageCodecTest |
| XXE/DTD/nesting/duplicate XML protection | MessageCodecTest.rejectsXmlDoctypeExternalEntitiesDuplicatesAndNesting, HTTP XML test |
| Durable insufficient-funds rejection | PaymentServiceTest.durableBusinessRejectionDoesNotMoveMoney |
| Source authorization and private lookup | PaymentServiceTest.cannotDebitUnownedAccountOrReadOthers; HTTP auth/privacy tests |
| Frozen accounts and restrictions | PaymentServiceTest.frozenDestinationAndAccountRestrictionsReject |
| Concurrent unique and duplicate requests | PaymentServiceTest.concurrentPaymentsNeverOverdraw, concurrentDuplicatesMoveExactlyOnce |
| Mid-transfer injected fault | PaymentServiceTest.injectedPostDebitFailureRollsBackEverything |
| Commit response loss and rediscovery | PaymentServiceTest.unknownCommitOutcomeCanBeResolvedByLookup |
| Persisted restart state | PaymentRestartIT.packagedServiceSurvivesRestartAndMeasuredLoad starts/stops/restarts real packaged service with the same PostgreSQL database |
| Immutable records and reconciliation corruption | PaymentServiceTest.ledgerAndFinalPaymentsAreImmutable, reconciliationDetectsCorruptedBalance |
| Negotiation, scopes/JWT issuer/audience/expiry, health, shared rate limit and correlation isolation | PaymentApiTest |
| Bounded lock retry and rejected SQL/ledger fault rollback | PaymentServiceTest.lockTimeoutHasBoundedRetriesAndNoDurableRejection, ledgerInsertFailureRollsBackDebitCreditAndPayment |
| 100 requests/second local smoke measurement | PaymentRestartIT: 300 unique transfers; full results in target/load-result.txt |

The k6 `scripts/load.js` exercises 100 new payments/second, p95 latency <500 ms and <1% failed HTTP requests. Representative-hardware results must be recorded for each release. Local fixture measurements are not a production SLA. Backups, external log retention, secret provisioning, TLS issuance, network policy and restore drills require deployment configuration described in OPERATIONS.md.

Final local verification (2026-10-28): `mvn -B verify` completed with BUILD SUCCESS, 42 Surefire tests plus one Failsafe packaged-process test, zero failures/errors/skips. The 300-transfer run at 100 requests/second returned all 201 responses and measured p95 10.986252 ms on this shared development host. Separate Compose validation and a transfer using restricted payments_app runtime permissions passed. Full output: `verify.log`, `target/surefire-reports`, `target/failsafe-reports`, `target/load-result.txt` and `target/local-smoke.log`.

The final review also verified oversized chunked/HTTP2 requests, extreme timestamps, aggregated stale-date issues and invalid Unicode surrogate rejection, protecting strict error handling and fingerprint stability. All test containers and the manually created Compose fixture were stopped and cleaned up. Production reference-environment sustained benchmarks and external backup/restore drills remain deployment validation tasks.
