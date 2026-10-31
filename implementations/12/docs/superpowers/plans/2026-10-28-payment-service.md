# Payment service implementation plan

**Goal:** Implement the complete supplied specification with autonomous technical decisions.
**Architecture:** Quarkus REST API, strict independent transport parser, immutable canonical command, synchronous JDBC transactions against PostgreSQL. Unique payment IDs resolve concurrent retries; deterministic account row locking protects funds; immutable double-entry records support reconciliation.
**Tech stack:** Java 21, Maven, Quarkus 3.15, PostgreSQL, Flyway, JWT, JUnit/REST Assured/Testcontainers.
**Spec:** `payment-processing-requirements.md`.

## Constraints and review focus
- JOD BigDecimal with exactly three canonical decimals, no rounding; all required fields and document ambiguities validated.
- Authenticate and authorize all API operations; isolate caller information, enforce request size, TLS, rate, and SQL timeouts.
- Commit receipt/rejection atomically; same ID remains bound to the original canonical command and initiator.
- Explicitly test duplicate fields, XXE, equivalent formats, concurrent overdrafts, rollback between balance writes, lost responses, durable lookup, and corrupt reconciliation.

## Tasks
- [x] Write parser/security and PostgreSQL API acceptance tests, observe absent implementation failure.
- [x] Implement domain command and safe parser/validator; run parser tests.
- [x] Implement Flyway schema, immutable ledger guards and baseline reconciliation.
- [x] Implement transactional orchestration and JWT-scoped endpoints; run PostgreSQL acceptance/concurrency/failure tests.
- [x] Add OpenAPI, container/Compose, CI, load script and operations documentation.
- [x] Run `mvn verify`, review requirements and record exact verification evidence.

Decisions: stateless replicas use shared PostgreSQL rate-limit counters. Business rejections preserve referenced identifiers even when nonexistent; only ledger account references are foreign keys. Production TLS terminates at Quarkus or trusted ingress with direct HTTP disabled. Database grants are provisioned separately from migration credentials.
