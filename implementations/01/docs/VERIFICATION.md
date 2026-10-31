# Verification record

The implementation uses real PostgreSQL 16 Testcontainers in `mvn verify`; test failures fail the build. Tests do not skip when Docker is unavailable. Java 21 is required.

## Acceptance coverage

| Requirement | Automated evidence |
|---|---|
| JSON success and exact 125.750 accounting | API JSON test; transfer test checks 374.250/225.750 |
| XML success and shared processing | XML-first API transfer; JSON/XML equivalent replay |
| Malformed/unknown/duplicate messages | Parser and API malformed/security tests |
| Required fields, precision, amounts, dates, same account | Parser validation tests collect safe issues |
| Insufficient funds and immutable rejection replay | PostgreSQL rejection/replay test |
| Frozen accounts and operational limits | PostgreSQL state/daily policy test |
| Source authorization, hidden balances/status | API principal tests and service unauthorized test |
| Idempotent replay and altered payload conflict | API cross-format replay and PostgreSQL conflict tests |
| Concurrent duplicate requests | Twelve parallel submissions, exactly one 201 |
| Concurrent balance contention | Ten parallel debits; no negative balance/lost credit |
| Atomic rollback | PostgreSQL trigger fails credit after successful debit; all financial rows roll back |
| Response loss / original outcome | Discard first result; reconstruct service, query and replay original ID |
| Restart durability | Restart PostgreSQL container, reconnect, verify original receipt/balances/reconciliation |
| Negotiation | 415, 406, quality-zero rejection, XML output and JSON default |
| XML security | DTD/external entity/duplicate elements rejected; parser additionally limits depth and rejects unknown elements |
| Auditability | Initiator, correlation, outcome and timestamps on durable payment; two linked ledger rows; JSON logs with MDC |
| Reconciliation | Corrupted balance fixture detected; balanced fixtures pass |
| Authentication | Ephemeral real RSA JWKS server: valid JWT plus invalid issuer/audience/expiration/signature and missing scopes |
| Health / contract | Live and DB readiness endpoints; served OpenAPI contract title |
| Bounded failure | Locked source exhausts bounded retries and returns transient error with no stored outcome |
| Ledger/baseline immutability | Direct SQL update/delete attempts rejected by database triggers |
| Replica rate budget | Separate service instances share PostgreSQL rate counter |
| Performance sample | 1,000 real HTTP transfers over 10 seconds at 100 offered requests/sec; asserts p95 <500 ms |

## Environment and commands

Run:

```
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH \
mvn -B verify
```

The final command/result and measured p95 are appended below once verification completes. `docker compose config --quiet` validates local setup. `python3 -m json.tool src/main/resources/META-INF/openapi.json` validates the checked-in contract. Source is formatted with google-java-format 1.24.0.

## Deployment validation boundaries

The HTTP load sample uses this shared development host, local PostgreSQL and a real signed test issuer; it excludes deployed TLS and external identity/network latency. The supplied 60-second k6 scenario should be run against representative production hardware and topology before declaring an SLA. Backup/restore drills, legal retention approval, production identity provisioning, certificate/secret management, least-privilege deployment grants and management-network policy are documented operational deployment responsibilities; no production service was deployed or contacted.

Application state is stateless: restart tests reconstruct the payment service and restart the database. A full container/orchestrator rolling-restart and primary failover exercise remains an environment-specific deployment check. Commit-response uncertainty is simulated by discarding a committed result; no artificial network proxy was inserted into a production-like cluster.

## Review

Self-review by the implementing agent checked the supplied acceptance criteria, SQL lock order, unique registration, final-record triggers, ownership and hidden lookups, transport coercion, XML resource prohibition, post-commit telemetry isolation and documentation. No separate reviewer was spawned so the requested numbered implementation remains isolated. The workspace is not a Git repository; no commit, branch merge or publication was performed.

Final verification: `mvn -B verify` completed with **BUILD SUCCESS**, **33 tests**, **0 failures**, **0 errors**, **0 skipped** on 2026-10-28. The final 1,000-request/100 requests-per-second sample measured **p95 7.325 ms**. Machine-readable test results are in `target/surefire-reports`; the full build record is `acceptance-verify.log`.

Container verification: `docker build -t payment-service-01:verification .` succeeded. Inspecting `.Config.User` returned `payment`, confirming the image runs without root. Compose and OpenAPI JSON validation also completed successfully.
