# Verification record — 2026-10-28

Executed from this numbered project only using the full JDK 21 installation:

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem mvn verify
```

Result: **BUILD SUCCESS**, 31 tests, zero failures, zero errors, zero skipped. Breakdown: 8 codec/domain validation tests, 18 PostgreSQL transfer/HTTP integration tests, 5 JWT/rate limit/HTTPS security tests. PostgreSQL 16.15 ran in Docker Testcontainers; no in-memory replacement was used.

The isolated regression test for a nonexistent-account rejection initially failed with a null owner lookup. It now returns non-enumerable 404 for an unrelated caller. The XML authentication error regression initially returned JSON; it now negotiates XML correctly. The full suite passed after both fixes. Compilation initially used a system Java 21 JRE without ct.sym/compiler support; the installed SDKMAN full JDK 21 resolves this. Testcontainers explicitly selects Docker API 1.44 to work with the Docker 29 daemon.

The dependency-free load driver `python3 scripts/load.py` ran 6,000 unique transfers at 100 arrivals per second for 60 seconds: **6,000 HTTP 201 responses**, p50 **3.938 ms**, p95 **44.012 ms**, maximum **230.956 ms**. PostgreSQL, client and service ran locally over loopback; other tasks shared the host. This measures the local reference workload, not remote or production deployment guarantees. Raw results are in `performance.json`.

The runnable shaded JAR was started against Compose PostgreSQL, stopped, and started again using an immutable copied JAR. After restart the original receipt exactly matched its persisted pre-restart receipt, source balance was 494.000 after the 6.000 aggregate debit, payment count was 6,000 and ledger entry count 12,000. Reconciliation reported zero issues. Packaged OpenAPI and authenticated Prometheus text output were served successfully. A separate transfer and reconciliation succeeded with the least-privilege runtime role and MIGRATE_ON_START=false. The Docker image `payment14:verified` also built successfully and its nonroot Compose container passed readiness and authorized balance queries. Temporary application processes, Compose containers and test volumes were stopped/removed after verification. Details are in `runtime-smoke.json`.

Acceptance coverage:

| Criterion | Evidence |
| --- | --- |
| 1 JSON success | HTTP integration asserts 125.750 yields 374.250 / 225.750 and linked entries |
| 2 XML success | HTTP integration executes XML and compares exact balances/amount output |
| 3 malformed input | Strict JSON/XML parser tests and HTTP malformed message checks |
| 4 invalid fields | Aggregated missing, UUID, amount, precision, date, same-account and text constraints |
| 5 insufficient funds | Durable rejection, unchanged balances and zero ledger entries |
| 6 account states | Frozen/missing account rejection and nondisclosure regression |
| 7 authorization | JWT issuer/audience/signature/expiry/scopes, source owner/grant and hidden lookups |
| 8 replay | Concurrent duplicate IDs, JSON/XML canonical retry, original receipt, aged replay |
| 9 conflict | Changed amount with same ID returns conflict and no new transfer |
| 10 concurrency | 24 duplicate submissions, competing debits, and opposite-direction transfers |
| 11 rollback | Injected SQLException immediately after source debit leaves no payment/ledger/balance change |
| 12 lost response | Raw socket discards receipt body after committed 201 headers; retry has exactly one financial effect |
| 13 restart durability | Fresh connection pool test plus packaged process restart against persistent PostgreSQL |
| 14 media negotiation | JSON/XML, q-value selection, 415/406 and negotiated authentication errors |
| 15 XML security | DTD, external entity, nested element, namespace and trailing document rejection |
| 16 audit | Initiator/correlation/timestamp in persisted payment and immutable linked ledger; structured HTTP events |
| 17 reconciliation | Deliberately corrupted account baseline agreement is detected; clean concurrent runs reconcile |

Operational decisions: the framework recommendation is implemented with Java's HTTPS server rather than Quarkus. Public JWKS rotation uses mounted files and rolling restarts. Quotas apply per replica; a gateway owns deployment-wide quotas. Archival/retention approvals, backups, restore drills and production secrets/certificates are operational procedures documented in operations.md; no automated financial deletion is included. No business functionality is intentionally left as a placeholder.
