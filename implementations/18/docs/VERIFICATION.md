# Verification evidence

Executed in `implementations/18` on 2026-10-28 using Java 21.0.5, Maven 3.9.9, Docker 29.9.2 and real PostgreSQL 16 Alpine containers.

```bash
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH \
mvn -B verify
```

Result: **BUILD SUCCESS**, 32 tests, 0 failures, 0 errors, 0 skipped. The last full run completed in 45.803 seconds. Full output: `verification.log`.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| com.example.payments.ApiTest | 9 | 0 | 0 | 0 |
| com.example.payments.ContractTest | 7 | 0 | 0 | 0 |
| com.example.payments.PaymentIntegrationTest | 14 | 0 | 0 | 0 |
| com.example.payments.ReferenceLoadIT | 1 | 0 | 0 | 0 |
| com.example.payments.RestartIT | 1 | 0 | 0 | 0 |

## Load measurement

```text
requests=1000 offeredRate=100/s duration=10s errors=0 p95Ms=5.624756 processors=16 java=21.0.5 postgres=16-alpine
```

This is a 10-second local reference measurement through the packaged service's HTTP endpoint, with signature verification and actual PostgreSQL commits, 30 warmup requests, one contended source account, and reconciliation afterwards. It meets the initial p95 <500 ms target on this local host. It is not a production capacity guarantee; perform the supplied 60-second k6 measurement with deployment TLS, issuer, database topology and reference hardware before release.

## Acceptance coverage

| Criteria | Evidence |
|---|---|
| 1 JSON success | API and integration tests verify 374.250 / 225.750 balances and two ledger entries |
| 2 XML success | XML REST receipt and cross-format replay tests |
| 3 malformed structure | Duplicate/type/unknown/deep document and malformed API cases |
| 4 field validation | Collected precision, purpose, account, UUID and timestamp failures |
| 5 insufficient funds | Durable rejection, unchanged balances, zero ledger records |
| 6 invalid accounts | Frozen, nonexistent and account policy cases with no disclosure |
| 7 authorization | Scope, owner, view restriction, JWT signature/issuer/audience/expiry tests |
| 8 replay | Same-ID races, original receipt equality and equivalent JSON/XML replay |
| 9 conflicts | Changed amount with same ID fails, no second ledger pair |
| 10 concurrency | 12 simultaneous duplicate requests; 12 competing debits; 16 reverse-direction transfers |
| 11 atomic rollback | PostgreSQL trigger fault after debit and failure on ledger insertion; all effects roll back |
| 12 response loss | Raw socket request whose response is deliberately unread; DB commit observed; replay after restart |
| 13 restart durability | Packaged service killed forcibly and restarted as a new JVM; receipt, balances, ledger preserved |
| 14 negotiation | JSON/XML success, 415 input and 406 output format cases |
| 15 XML security | DTD, external entity, XInclude/namespace and repeated-element attacks rejected |
| 16 auditability | Persisted initiator/correlation/timestamps and linked ledger, structured outcome logs; immutable financial records |
| 17 reconciliation | Both corrupted account balance and privileged deletion of a ledger credit detected |

Additional checks: exhausted lock contention yields bounded 503 with no persisted rejection; immutable ledger/payment mutation is rejected; deferred database constraint rejects unbalanced completion; rate limiter returns 429/Retry-After; readiness returns 503 during a stopped database and recovers on restart at a stable endpoint. The final packaged artifact contains no test signing keys.

## Delivery and limits

Delivered Maven source/build, REST API, OpenAPI, Flyway migration, Compose PostgreSQL, explicit development seed, nonroot Dockerfile, GitHub Actions workflow, unit/API/database/process tests, load scripts, setup and operational guides. No application requirement gaps were identified in the final review.

Production provisioning remains environment-owned: configure the real OIDC issuer/JWKS/audience, managed credentials, TLS certificates, dedicated migration/runtime DB roles, restricted management ingress, backup/WAL infrastructure, legal retention policy and backup restore drills. These procedures are documented; no production deployment, external identity provider setup, backup drill or Docker image build was performed here. Review was by the implementing agent; no extra agent beyond the assigned isolated implementation was created.
