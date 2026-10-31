# Verification evidence

Verified on 2026-10-28 using Java 21.0.5, Maven 3.9.9, Docker 29.9.2 and PostgreSQL 16.15. All tests use actual PostgreSQL, with no H2 substitution and no skipped container tests.

`mvn -B verify`: **BUILD SUCCESS; 30 tests, 0 failures, 0 errors, 0 skipped**. Suite includes 18 PostgreSQL/application integration tests, 8 parser tests, 1 negotiation test, 2 rate/transport tests and 1 real RSA JWT validation test. The API integration fixture injects an authenticated test principal; JWT cryptographic/issuer/audience/expiration checks run separately against a real local JWKS HTTP endpoint. The live stack additionally validates genuine Keycloak-issued tokens through the production security chain.

`docker build -t payment-service-11:verification .`: successful Java 21 multi-stage build; runtime user is `payment`, not root. `docker compose -p payment11 config --quiet`: valid. Local PostgreSQL and Keycloak services were started and the built app container was exercised using real HTTP and bearer tokens. Test ports were isolated at 18111/18211/18311.

Live checks verified:

- A stored receipt and balances remained readable after stopping the original JVM and starting the container against the same PostgreSQL volume.
- A new JSON payment returned 201; equivalent XML with the same UUID returned 200 and the original amount, with only one debit.
- PostgreSQL was paused deliberately: liveness remained UP, readiness returned 503 within 3.03 seconds. Readiness recovered to UP after unpausing PostgreSQL.
- A reference HTTP load sent 100 valid payments/second for 10 seconds, through actual JWT security and committed PostgreSQL transactions. Exact measured output is [load-result.json](load-result.json). There were no failed requests; the final container run measured p95 **11.298 ms**, max **135.392 ms**, with **1,000/1,000 successful requests** in 9.995 seconds. This was below the 500 ms p95 target. The initial cold-key-cache run measured p95 121.516 ms and max 452.532 ms. These are local engineering measurements, not production capacity guarantees.

## Acceptance traceability

| Criterion | Evidence |
|---|---|
| 1 JSON success/accounting | `successReplayConflictAndAudit`, `jsonHttpAndVisibility`: exact 374.250/225.750 balances, one payment and two entries |
| 2 XML success | `xmlHttpNegotiationAndSecurity`, `formatIndependentFingerprint`, live JSON/XML replay |
| 3 Malformed input | Parser malformed/duplicate/wrong-type/unknown-field tests; bounded strict parser runs before transaction |
| 4 Field validation | `invalidAmountsAndOffsetlessDate`, precision and collected-issues tests, `httpCollectsTimeAndAmountValidationIssues` |
| 5 Insufficient funds | `rejectedReplayIsDurable`: no ledger entries; original rejection returned after funding changes |
| 6 Account state/existence | `authorizationAndFrozenAccounts`, `missingAccountsRestrictionsAndScopes` |
| 7 Authorization | Ownership/delegate tests, scope/visibility tests, real JWT validation, TLS/rate tests |
| 8 Replay | `successReplayConflictAndAudit`, `duplicateRacesAndResponseLoss`, date-window replay test, live cross-format replay |
| 9 Payload conflict | `successReplayConflictAndAudit`: 409, no second transfer |
| 10 Concurrency | 16 duplicate submissions and 10 competing debit requests: one duplicate effect; five funded successes; conserved total |
| 11 Rollback | `rollbackAfterDebit`: fault after source update rolls back payment registration, source balance and all related data |
| 12 Lost response | `responseLostAfterCommitCanBeSafelyRetried`: test-only transport filter throws after commit; retry returns 200, one effect |
| 13 Durability | `restartDurability`: new application context reads/replays the stored result; additional actual JVM-to-container restart check |
| 14 Media handling | HTTP JSON/XML, unsupported 415/406, weighted Accept exclusions in `NegotiationTest` |
| 15 XML security | DTD/XXE, duplicate elements and namespaces rejected; resolver always prohibits external access |
| 16 Audit | Initiating subject/correlation persisted, two signed ledger entries, structured operational logging and immutable rows tested |
| 17 Reconciliation | `reconciliationDetectsCorruption`: deliberately changed account balance is reported; healthy transfers reconcile |

The migration's deferred constraints independently reject unbalanced/misdirected completed ledger pairs and unfinished PENDING records at commit. Ledger and finalized-payment triggers prohibit updates/deletes. `locksHaveBoundedRetryAndDoNotPersistTransientFailure` proves lock timeout exhaustion yields a transient 503 with no payment registration and is safe to retry. Administrative reconciliation and retention visibility are covered by `rejectionQueriesAndAdministrativeReports`.

## Operational boundaries

Production rollout must supply trusted OIDC endpoints, native TLS or an appropriately secured TLS gateway, real secrets and least-privilege runtime/migration roles. The Compose credentials and local seed profile are development fixtures. Rate counters are bounded per process; aggregate replica quotas belong at the gateway. Backups, legal retention selection, point-in-time recovery configuration, restore drills, alert routing and representative production load qualification are operational procedures described in [operations.md](operations.md), not claims that those external systems were provisioned here. No background deletion or unauthenticated account-provisioning API is provided. Spring Boot is used instead of recommended Quarkus; all required HTTP, transaction and persistence behavior is implemented.

Final verified image: `sha256:548dd94c47cb296b797d6cfb4da25c04e75830315480318f8bac0b9eb9a04c52`. Temporary project `payment11` containers and its test data volume were removed after verification; the built image and source artifacts remain.
