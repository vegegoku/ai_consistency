# Payment Service Implementation Plan

**Goal:** Implement the supplied payment-processing specification in an isolated Maven service.
**Architecture:** A stateless Quarkus API maps JSON and hardened XML to one immutable command. Explicit JDBC transactions reserve UUIDs, lock accounts in ascending order, authorize under lock, and atomically persist balances, immutable payments and balanced ledger entries. PostgreSQL unique keys resolve duplicate submissions across replicas.
**Tech Stack:** Java 21, Quarkus 3.8, PostgreSQL, Flyway, JWT, JUnit, Testcontainers.
**Spec:** ../../payment-processing-requirements.md

The user authorizes autonomous design and execution without approval. Chosen approach is JDBC over ORM for visible locking/transaction boundaries; asynchronous or distributed settlement is outside scope.

- [x] Establish Maven build and parser tests; verify missing implementation fails, implement strict parser and aggregate validation.
- [x] Establish PostgreSQL acceptance tests; implement migrations and atomic transactional service with replay/conflict, rejection durability and bounded retry behavior.
- [x] Implement JWT/scopes, account authorization, API negotiation/errors, limits, health, metrics and audit correlation.
- [x] Verify JSON/XML parity, races, rollback and reconciliation against PostgreSQL.
- [x] Deliver Compose, container, OpenAPI, CI, load harness, setup and operations documentation; run mvn verify.

Review focus: duplicate XML/JSON values, precision and offset timestamps; inaccessible account information; concurrent duplicate/overdraw; rollback following debit; tampering detected by reconciliation. All monetary operations use BigDecimal and database numeric(19,3).
