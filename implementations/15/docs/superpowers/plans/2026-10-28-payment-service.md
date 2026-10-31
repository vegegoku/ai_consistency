# Payment Service Implementation Plan
> Native autonomous implementation, authorized by AGENTS.md.
**Goal:** Deliver the complete payment backend specified in payment-processing-requirements.md.
**Architecture:** Spring MVC transports, immutable domain command, transactional JDBC application service, PostgreSQL constraints and Flyway migrations.
**Tech Stack:** Java 21, Maven, Spring Boot, PostgreSQL, JUnit, Testcontainers.
**Spec:** ../../../payment-processing-requirements.md
## Global Constraints
Exact decimal JOD scale 3; maximum 10,000.000; 64 KiB; 5 minute future/30 day past window. Authenticated and authorized queries. No partial transfer or duplicate financial effect. No approval questions.
## Review Focus
Duplicate and nested input cannot bypass strict parsing; concurrent duplicates wait for stored outcome; source/destination locks are deterministic; rollback injection leaves no rows; unrelated users cannot enumerate payments/accounts.
## Tasks
1. Parser/validation: write MessageParserTest; observe missing implementation; implement strict shared command, validation and fingerprint; rerun.
2. Persistence/application: write PostgreSQL service tests including balances, ledger, replay, conflict, rejection, races and injected failure; observe failure; implement schema, immutable triggers and transactional JDBC service; rerun.
3. HTTP/security: write authenticated HTTP contract tests; implement scopes/JWT, HTTPS, byte/rate limits, negotiation/errors, lookups and reconciliation; rerun.
4. Delivery: add OpenAPI, Compose/Keycloak/dev seeds, Dockerfile, CI, load driver, README and runbook; run mvn verify and inspect result before completion.

## Execution result
All four tasks implemented. Final Maven verification: 28 tests, no failures/errors/skips. Real Compose/OIDC, restart, database outage recovery, reconciliation and 100 rps load checks passed. See `../../../VERIFICATION.md` for evidence and deployment boundaries.
