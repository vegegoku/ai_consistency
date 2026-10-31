# Payment service implementation plan

Goal: implement payment-processing-requirements.md autonomously within folder 18.
Architecture: Quarkus REST Java 21, strict transport parser into one immutable command; explicit PostgreSQL JDBC transactions with unique payment registration and deterministic account row locking. JWT signatures, issuer, audience, expiry and scopes guard APIs. Versioned Flyway migrations enforce monetary and ledger invariants.

Ruling: use pinned Quarkus 3.15.3 LTS, Java 21, explicit JDBC rather than ORM because transfer SQL and lock order need to be obvious. No user gates or additional agents, per supplied instructions.

- [x] Contract tests first: strict duplicate/type/unknown/depth/XXE parsing and collected validation; canonical fingerprints independent of input format.
- [x] Implement parser, immutable command, error contract, size/timestamp/precision limits.
- [x] PostgreSQL integration tests first: exact balances, repeat/conflict, durable rejection, parallel duplicate and competing debits, rollback fault injection, reconciliation corruption.
- [x] Implement migration, repository and one transactional transfer service, bounded lock/statement timeouts and retries.
- [x] API/security tests first: bearer validation, scopes, ownership, JSON/XML negotiation, balances/status visibility, original receipts after response loss.
- [x] Implement REST, request context/correlation, local rate limits, safe exception mapping, metrics, health and reconciliation.
- [x] Supply OpenAPI, Docker Compose with dev-only seeds, container, CI, k6 load test and operational/setup documentation.
- [x] Run mvn verify against PostgreSQL; inspect failures, fix and rerun. Record actual evidence and any gaps.

Review focus: huge amount exponent; Unicode/duplicate XML elements; timestamp boundaries; simultaneous reverse-direction transfers; unauthorized replay and receipt lookup.

## Execution record

- Contract tests were introduced before transport implementation; first build failed because domain/parser types did not yet exist. Strict parser tests subsequently passed.
- PostgreSQL and API tests were introduced before their service/resource implementations. The real database run caught a pooled read-only state leak after reconciliation; removal of that connection state plus explicit writable transaction setup fixed it.
- A Docker API mismatch (test client default 1.32 versus server minimum 1.40) was resolved with test JVM API 1.44.
- Authorization tests exposed framework paths including a leading slash; normalized route matching now guards all versioned endpoints. JSON/XML tests exposed a client-provided XML charset; UTF-8 enforcement applies only to JSON while StAX honors XML document encoding.
- Runtime review: database constraints independently enforce nonnegative balances, ledger signs, append-only history and deferred balanced completed payments. Tests inject credit and ledger faults, create a corrupt privileged fixture, and test exhausted locks.
- Packaged-process tests exercise unobserved committed responses, forced process termination/restart, PostgreSQL outage/readiness failure and recovery, plus reference load measurement.
- Ruling: PostgreSQL owns global rate counters and source account daily limits to preserve behavior across stateless replicas.
- Ruling: unknown or unauthorized source identifiers yield 403; undisclosed payment/balance lookups yield 404. Only the initiating principal sees a payment receipt. This avoids disclosing third-party records.
- Ruling: store account identifiers without payment-table FKs because business rejections for unknown recipients need durable records; ledger entries retain restrictive FKs to real accounts/payments.
- Ruling: retention is a documented configurable minimum with indefinite financial-record preservation, rather than automatic financial deletion.
- Final review: author self-review against the source requirements; no independent child reviewer was created because this task is one isolated numbered implementation within the user's specified 20-agent run. No Git repository exists in the provided project root, so no commits or branch operations were performed.

Fresh build evidence is in `docs/VERIFICATION.md` and `verification.log`.

- Failure-injection fixture ruling: pin the restart test database host port; Docker reallocates ephemeral host bindings on container restart, which otherwise tests a changed endpoint rather than recovery of a stable database endpoint.
