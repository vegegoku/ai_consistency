# Payment service implementation plan

**Goal:** Implement payment-processing-requirements.md in this isolated numbered directory.
**Architecture:** Java 21 HTTP service, strict JSON/StAX transports, immutable domain commands, JDBC PostgreSQL transactions. One deployment with Flyway, Nimbus RS256 authentication, Micrometer and OpenTelemetry. Native JDK HTTP replaces the recommended Quarkus framework to keep the transport surface explicit; the requirements describe Quarkus as recommended rather than mandatory.
**Execution:** Autonomous inline execution; all design choices approved by AGENTS.md, no human approval gates.

1. Write parser and validator tests, observe missing implementation failure, implement domain and shared codec. Cover duplicate keys/elements, XXE, scale, canonical fingerprints and field issue aggregation.
2. Write PostgreSQL transaction tests, observe failure, implement Flyway schema and service. Cover 125.750 transfer, frozen/missing accounts, duplicate races, overdraft races, durable rejection, rollback injection, replay after response loss/restart, ledger reconciliation and immutability.
3. Write HTTP security/media tests, observe failure, implement JWT, rate limiting, HTTPS deployment, health, endpoints, JSON/XML errors, structured logs, metrics and tracing.
4. Provide OpenAPI, Compose, seed and token tools, CI, load test and operational instructions. Run mvn verify against real PostgreSQL, inspect packaged jar, record results and limits.

Global constraints: JOD exact scale 3; no overdraft; unique payment ID; immutable ledger; bounded DB lock and retry waits; no raw request/credentials in logs; only numbered directory 14 is writable.
Review focus: trailing documents and nested XML; different-principal duplicate ID; rejection replay after timestamp window; failure between balance updates; DB-unavailable readiness and transient errors.

## Execution ledger

- Shared codec implemented and tested, including canonical JSON/XML equivalence and strict XML hardening.
- PostgreSQL migrations and atomic transfer service verified against real PostgreSQL with deterministic locks, retries, immutable records and deferred ledger checks.
- API, JWT/scopes/ownership, TLS, rate limiting, health, metrics and traces implemented; 31 tests pass.
- Documentation, OpenAPI, Compose, CI, load driver and measured results delivered.
- Final self-review corrected null ownership on hidden rejected-payment lookup and XML auth error negotiation; regressions observed RED then full suite GREEN. No extra agent was spawned because this task requires isolation.
- Ruling: JDK HTTPS transport replaces recommended Quarkus; functional contract unchanged, manual HTTP integration is maintained here.
- Ruling: Financial retention uses externally operated archival with a configured minimum; immutable records are never automatically deleted.
- Verification: see docs/verification.md, performance.json and runtime-smoke.json.
