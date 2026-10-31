# Verification evidence — 2026-10-28

## Build

Executed from this project directory:

```sh
env JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
  /home/vegegoku/.sdkman/candidates/maven/current/bin/mvn -B verify
```

Result: **BUILD SUCCESS**, 28 tests, 0 failures, 0 errors, 0 skipped; final run 16.822 seconds. This is the portable documented `mvn -B verify` command with this workstation's Java 21 JDK explicitly selected. The workstation's default Maven Java is 17, so leaving it implicit would be incorrect. The built executable is `target/payment-service-15-1.0.0.jar`.

Tests: 15 PostgreSQL/application/API integration cases, 6 strict-parser cases, 4 boundary/security policy cases, 1 infrastructure/pool-exhaustion case, 1 full application-context restart case and 1 actual signed-JWT verification case. Several cases exercise tables of invalid payloads or concurrent callers.

## Acceptance coverage

| Criterion | Verification |
| --- | --- |
| 1 JSON success | Exact 500.000/100.000 to 374.250/225.750 accounting, receipt and two entries; real JWT container request also passed |
| 2 XML success | HTTP XML input, canonical XML response and JSON replay of the identical instruction; real container request passed |
| 3 Invalid structure | Duplicate, unknown, nested, wrong typed and malformed JSON/XML rejected without persistence |
| 4 Invalid fields | Missing fields, zero/negative/excess precision, invalid/old offset timestamp, UUID, same account and invalid Unicode |
| 5 Insufficient funds | Durable rejection, original rejection replay, unchanged balances and no ledger |
| 6 Invalid accounts | Missing destination concealed with 403; FROZEN and CLOSED accounts rejected; no movement |
| 7 Authorization | Unrelated subject rejected; explicit grant, destination allow-list and UTC daily limits checked |
| 8 Idempotency | Original receipt, mixed JSON/XML replay, concurrent duplicates, historical retries after date-window expiry |
| 9 Conflict | Altered amount under the same ID rejected without another transfer |
| 10 Concurrency | Eight duplicate callers cause one transfer; eight competing 100.000 payments from 500.000 cause exactly five transfers |
| 11 Rollback | PostgreSQL trigger fails after debit; balances, registration and all ledger rows roll back |
| 12 Failure uncertainty | Real commit followed by an injected lost-ack exception returns 503; same-ID retry returns original completed outcome |
| 13 Durability | Entire application context stopped/restarted against persisted PostgreSQL; container image recreation also preserved original receipt |
| 14 Media negotiation | JSON/XML input/output; 415 unsupported input, 406 unsupported response and Accept quality/exclusion checks |
| 15 XML security | DTD/external entity, namespace, duplicate and nested-element payloads rejected by hardened parser |
| 16 Auditability | Persisted authenticated subject, correlation ID, UTC timestamps and paired immutable ledger; structured commit/error logs |
| 17 Reconciliation | Clean state reports healthy; deliberately corrupted persisted account balance is detected |

## Containers, real OIDC and failure recovery

`docker compose config -q` succeeded. `RATE_LIMIT_PER_MINUTE=12000 docker compose up --build -d` built the multi-stage image and started PostgreSQL, Keycloak and the service. Runtime: Temurin 21.0.12.1; PostgreSQL 16.15. Actual Keycloak-signed access tokens with explicit subject/audience/scopes were used for JSON and XML POSTs, original receipt replay, authorized balance lookup and admin reconciliation. JSON first returned 201, replay 200, and balance 374.250. XML returned 201 and an amount string of 1.250.

Stopping PostgreSQL after warming the connection pool returned **503 TEMPORARILY_UNAVAILABLE**. Starting it restored readiness. Retrying that instruction with the same ID returned 201, then 200 on its replay. Reconciliation was healthy. This exercised live connection loss in addition to automated pool and commit-uncertainty fault injection.

After the final load run, reconciliation returned `healthy: true`, `paymentAnomalies: []`, `balanceAnomalies: []`. The container image restart also returned the original stored receipt unchanged. Disposable verification containers, network and the newly created development database volume were removed with `docker compose down --volumes`; a fresh local startup receives the documented seed balances.

## Performance

Executed against the final production code in one local Compose replica, with a pool of 20, loopback HTTP, PostgreSQL and the load generator on the same shared Linux x86_64 development host (16 logical CPUs). A test-only explicit grant allowed alternating 0.001 JOD transfers between the two seeded accounts. The principal rate limit was 12000/minute.

```sh
RATE=100 SECONDS=30 python3 scripts/load.py
```

Result: **3,000/3,000 HTTP 201**, target 100 requests/second, elapsed 29.998 seconds, **p95 7.737 ms**, `passed: true`. Machine-readable results are in `docs/load-result.json`. These are local engineering measurements, not a production hardware guarantee; TLS, production topology and operational load require representative staging measurement.

## Deployment boundaries

No application implementation gaps are known. Production certificate/issuer/audience assignment, secret-manager wiring, gateway-wide rate limiting, backup infrastructure, legal retention decisions and actual restore drills require deployment/operator systems; configuration and procedures are provided in README and OPERATIONS. The service has no administrative fund mutation, record-deletion or trigger-bypass HTTP endpoint. CI configuration runs `mvn -B verify`; the local command passed, but no remote CI provider was invoked from this task.
