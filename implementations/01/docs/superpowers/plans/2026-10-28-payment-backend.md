# Payment Backend Implementation Plan

**Goal:** Implement the supplied payment-processing specification within this numbered directory.
**Architecture:** Stateless Quarkus API, strict transport parser, immutable canonical commands, PostgreSQL JDBC atomic transactions. Insert-on-conflict registers idempotency; ordered row locks serialize account changes. Final outcomes and signed ledger entries commit together.
**Tech Stack:** Java 21, Maven, Quarkus 3, PostgreSQL, Flyway, JUnit 5/Testcontainers.
**Spec:** ../../payment-processing-requirements.md (local copy in project root).
**Authorization:** User explicitly directs autonomous decisions and implementation without approval.

## Tasks
- [x] Write parser/domain and PostgreSQL acceptance tests; verify initial compilation fails on missing classes.
- [x] Implement canonical validation and secure JSON/XML parsing.
- [x] Implement schema, immutable ledger, atomic transfer, replay, rejection, rollback/retry and reconciliation.
- [x] Implement JWT authorization, REST negotiation/error mapping, limits, health and metrics.
- [x] Run Maven verification against PostgreSQL, fix observed failures.
- [x] Supply OpenAPI, Docker deployment, CI, load tool and operational documentation; review requirements and record verification limits.

## Review focus
Duplicate payload races; same ID across callers; forbidden source existence leakage; parser entity/duplicate attacks; transaction failure after debit.

## Decisions
Recommended framework retained. Explicit SQL selected over ORM to make locking/transaction behavior visible. Customer subject claim owns accounts; DB grants additionally allow debit/destination access. TLS required in production. Development seeds are profile-scoped.

## Execution ledger

Parser/domain and transaction tests were implemented before financial processing. Missing PaymentService failed compilation, then PostgreSQL tests exercised real transactions. Regressions exposed request-context field access bypassing CDI proxies, default authentication error text, build-time OIDC test disabling, zero-quality Accept handling, Docker API compatibility, and ephemeral port rebinding on database restart. Each observed issue was corrected and the full suite rerun. Local planning/review was autonomous as expressly instructed.
