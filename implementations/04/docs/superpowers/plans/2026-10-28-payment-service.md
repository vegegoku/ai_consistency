# Payment service implementation plan

The supplied specification and AGENTS.md authorize all implementation choices without approval.

Architecture: Java 21 / Quarkus REST, explicit PostgreSQL JDBC transactions, Flyway, JWT bearer security, immutable domain records. An INSERT under a unique UUID key resolves concurrent identical submissions; deterministic row locking prevents overdrafts. No asynchronous financial work.

1. Write parser/domain tests, demonstrate failure, implement strict JSON and hardened XML parsing and aggregated domain validation.
2. Write PostgreSQL tests for success, rejection, replay, conflicts, races, rollback and reconciliation, implement schema and transactional service.
3. Implement authenticated REST endpoints, rate limits, correlation/error handling, metrics, health and API specification.
4. Add deployment, seed and operational documentation; run `mvn verify` on Java 21 and fix failures.

Review focus: rejected replay preserves its original timestamp; type coercion and XML attributes are rejected; duplicate requests wait on the database constraint; authorization precedes receipt disclosure; transient SQL errors never become durable rejection records.
