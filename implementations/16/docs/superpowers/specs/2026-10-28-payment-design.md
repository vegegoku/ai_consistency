# Payment service design
The requirements specification is authoritative. The caller explicitly delegates design and approval decisions.

Use Java 21, Spring Boot 3.4, JDBC, PostgreSQL, Flyway, Jackson and hardened StAX. Spring is chosen over recommended Quarkus because its existing dependencies and explicit transaction abstraction make the complete service and tests straightforward. Both are valid single service implementations. JPA offers no advantage to the critical explicit locking path.

Transport parses strict JSON or flat XML into the same immutable command. A transaction inserts the unique payment ID, locks accounts in sorted order, authorizes the principal against ownership or grants, validates policy and balances, and writes balanced ledger rows and final payment status. A duplicate insert waits for the winner, then returns the existing immutable outcome if the fingerprint and principal agree. Business failures commit a rejection without financial changes. Infrastructure errors roll back and have bounded retries.

OIDC JWT issuer/audience/signature/expiry validation, scoped authorization, deployed HTTPS, body limits, parser depth limits, rate limits and safe error handling protect the API. Receipt lookups require the initiating principal or authorized account holder. Reconciliation compares account balances with opening balances and signed ledger totals. Operational docs cover backups and retention.

Verification uses unit parser tests and real PostgreSQL integration tests for API security, JSON/XML, concurrency, rollback, durability, and reconciliation. Load testing is provided separately; targets require representative hardware.
