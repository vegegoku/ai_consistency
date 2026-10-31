# Payment service implementation plan

Goal: Implement every behavior in payment-processing-requirements.md in this isolated directory.
Architecture: A single Java 21 Quarkus deployment uses strict transport readers, an immutable command, and JDBC transactions. PostgreSQL unique IDs serialize duplicates; sorted row locks serialize competing debits. JWT subjects and database account permissions authorize access. A Flyway migration provides immutable ledger and payment records, with opening balances for reconciliation.

Autonomous design decisions: account ownership uses customer ID equal to JWT subject, explicit permissions supplement ownership, `payments:submit` and `payments:read` scopes govern operations, and every destination requires an account-level receive permission or owner/public recipient policy. Account restrictions include per-transfer and daily outgoing limits. Business rejections are durable; failed transactions are not. Production requires TLS; test/dev alone permit HTTP.

1. Write parser/validation tests, run red, implement strict JSON/XML and canonical command/fingerprint, run green.
2. Write PostgreSQL transfer tests for exact accounting, rejected replay, concurrency, rollback and reconciliation; run red; implement migration/repository/service; run green.
3. Write authenticated API tests for JSON/XML, non-disclosure, media, malformed input, JWT and transient responses; run red; implement resource/filter/error contract; run green.
4. Add deployment, OpenAPI, load test, local seeds and operational docs. Run `mvn verify` on Java 21, inspect results, self-review requirements, resolve defects.

Review focus: typed coercions and duplicate XML elements; cross-principal idempotency reads; commit-before-response failures; precision/fingerprint normalization across formats; ledger tampering and opening-balance discrepancies.

Verification: all automated tests run on real PostgreSQL through Testcontainers; test CDI alternative injects failures after debit without production toggles. CI runs `mvn -B verify`. Load target measured separately using the supplied k6 test in a representative environment.

Completion record: all four tasks implemented. Final Maven verify passed 32 tests against PostgreSQL, including the packaged restart/outage test. Parser, authentication routing, historic replay, oversized-body errors, and policy issue aggregation were verified and corrected during execution. Local 100 requests/second load measurement and healthy reconciliation are recorded in docs/verification.md. No extra agents or external approval were used.
