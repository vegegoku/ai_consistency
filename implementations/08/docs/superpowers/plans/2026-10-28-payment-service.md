# Payment Service Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; the user requires autonomous decisions and isolation in numbered directory 08.

**Goal:** Secure, synchronous, exactly-once JOD internal transfers.
**Architecture:** Quarkus REST boundary, strict parsers and immutable command, explicit JDBC transaction, PostgreSQL constraints and ordered row locks. Authentication verifies bearer JWT signatures, issuer, audience, expiry and scopes. Account permissions are read from persisted relationships.
**Tech Stack:** Java 21, Maven, Quarkus 3.15.3, PostgreSQL, Flyway, Jackson, Micrometer, OpenTelemetry, JUnit and Testcontainers.
**Spec:** ../../../payment-processing-requirements.md

## Global Constraints
- Work only within implementations/08; no production secrets or unrelated changes.
- JOD exact scale 3, positive amount at most 10,000.000, 64 KiB bodies.
- Unique UUID identity, ordered account locks, atomic ledger and balance updates.
- Run `mvn verify`; PostgreSQL tests must fail rather than silently skip without Docker.

## Review Focus
- Duplicate JSON/XML members and invalid types must fail before persistence.
- Concurrent duplicate identities and opposite-direction transfers preserve money.
- Failed insertion or injected post-debit fault must roll back all financial state.
- Unauthorized lookup/replay must conceal payment/account existence.
- Historical replay remains stable beyond timestamp validation window.

### Task 1: Strict domain command and parser
Files: domain/PaymentCommand.java, api/MessageCodec.java, api/ApiFailure.java, test/MessageCodecTest.java.
- [x] Write failing parser tests for precision, types, duplicates, XXE, normalized fingerprints and aggregated issues.
- [x] Run tests to establish missing implementation.
- [x] Implement strict shared parsing and validation.
- [x] Run parser tests to green.

### Task 2: Transactional persistence and accounting
Files: persistence migrations, application/PaymentService.java, persistence/PaymentStore.java, test/PaymentServiceTest.java.
Interfaces: PaymentCommand -> PaymentService.transfer(command, principal, correlation), immutable Outcome.
- [x] Write PostgreSQL tests for canonical success/replay, rejection, concurrency, rollback, durability and reconciliation.
- [x] Run tests before implementation.
- [x] Implement idempotency insert-on-conflict, locked business validation, balances, append-only ledger and rejection persistence.
- [x] Run PostgreSQL tests to green.

### Task 3: HTTP security, observability and delivery
Files: api/PaymentResource.java, security/RequestSecurity.java, observability/Telemetry.java, application.properties, OpenAPI, README, operations, compose, CI, load script.
- [x] Write HTTP tests for JSON/XML, JWT scopes, privacy, errors, negotiation and health.
- [x] Run tests to establish absent endpoints.
- [x] Implement authenticated HTTP boundary, bounded input/rate limits and common safe error contract, telemetry and reconciliation.
- [x] Add containers, JWT development setup, operations, API and load documentation.
- [x] Run `mvn verify`, inspect results and review all acceptance criteria.

## Rulings
- Explicit user instruction auto-approves decisions, overriding skill review/approval pauses.
- The numbered directory already provides required isolation; do not create shared git state or commits outside it.
- Use explicit JDBC transactions rather than ORM to keep financial locking and atomic commit visible.

## Execution record
- Implemented all three tasks inline in the assigned directory, with tests written and missing behavior verified before implementation.
- PostgreSQL verified exact transfer accounting, concurrent duplicate IDs, competing/opposite-direction transfers, immutable records, durable rejections, injected mid-transfer and ledger failures, lock timeout retries and reconciliation corruption detection.
- HTTP verified strict media contracts, JWT verification, scope/account authorization, privacy, body limits, shared rate limits, correlation isolation and OpenAPI.
- Packaged application restart and 300-transfer 100rps smoke load passed. Local Compose passed with separate migration and runtime users.
- Ruling: Use random test HTTP ports, because other isolated workers can run Quarkus suites concurrently on this host.
- Ruling: Enforce size through early declared-length rejection and bounded InputStream reads; disable the framework's duplicate pre-REST limit so streaming requests retain the structured error contract.
- Ruling: A valid instruction timestamp uses years 0001–9999; this prevents numeric overflow before database registration while preserving historical retries.
- Final review: author review with targeted RED→GREEN regressions for valid identity in conflict errors, aggregated timestamp issues, extreme timestamp values, oversized streaming requests and invalid Unicode surrogate rejection before fingerprinting. No code changes outside folder 08 and no shared git operations were performed.

Final verification: Maven BUILD SUCCESS; 43 total tests, zero failures/errors/skips; local 100rps/300-transfer p95 10.986252 ms. Compose config and least-privilege runtime smoke passed.
