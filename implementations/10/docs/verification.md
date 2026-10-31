# Verification evidence

Verified on 2026-10-28 using Temurin Java 21.0.5, Maven 3.9.9, Docker Engine 29.9.2 and disposable PostgreSQL 14 databases.

Command:

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH \
mvn -B clean verify
```

Result: **BUILD SUCCESS**, 22 Quarkus/JUnit API/database tests plus one packaged-application integration test, zero failures, errors or skips. CI only needs Java 21, Maven and Docker, and runs `mvn -B verify`.

Acceptance coverage:

| Criteria | Evidence |
| --- | --- |
| JSON and XML accounting | Exact 125.750 transfer; persisted balances 374.250 and 225.750; two ledger entries |
| Malformed fields/documents | Unknown fields, duplicate keys/elements, wrong JSON types, missing fields, amount/date/purpose failures |
| Business rejection and authorization | Insufficient funds, frozen/missing accounts, missing scope, hidden balance and initiating-principal payment visibility |
| Idempotency | Canonical decimal and cross-format replay; original rejected outcome; changed payload conflict; replay remains valid after age/amount policy changes |
| Concurrency | Eight concurrent duplicate requests produce one first completion; eight competing debits produce four completions with no overdraft |
| Rollback | Failure after debit, after credit and after ledger insertion rolls back balances, payment and entries |
| Uncertain response | Failure after commit returns transient error; original ID replay yields the stored receipt |
| Bounded locking | A deliberately held account lock exhausts bounded retries, returns 503 and leaves no payment or balance changes |
| Restart durability | Packaged process is stopped and restarted against the same PostgreSQL instance; original receipt and balance remain; replay does not debit twice |
| Media and XML security | Unsupported request/response types, DTD, external entities, namespaces/XInclude, nested/duplicate XML elements |
| JWT security and limits | Missing token, wrong issuer, audience and expired token, scopes, authenticated rate limit and oversized body |
| Audit/reconciliation | Authenticated initiator and correlation persisted; completed payment/ledger updates forbidden; corrupted account fixture detected |
| Health | Live and PostgreSQL readiness probes return healthy during normal operation |

The packaged integration test also sends **500 successful transfers at 100 requests/second over five seconds** to the restarted application, checks resulting balances, and requires zero reconciliation discrepancies. Last observed p95 latency: **6 ms**, compared with the initial 500 ms engineering target. This is a local shared-host reference measurement, not a production SLA or a long-duration stress test. Each verify run writes its measured result to `target/reference-load-report.txt`; an extended 60-second k6 scenario is supplied in `dev/load.js`.

Production acceptance also requires environment-specific deployment validation: real OIDC issuer/JWKS configuration, TLS certificates, least-privilege roles, backup/restore drills, retention approval, monitoring and representative hardware load testing. These are operational procedures documented in `docs/operations.md`, not actions performed against production by the test suite.
