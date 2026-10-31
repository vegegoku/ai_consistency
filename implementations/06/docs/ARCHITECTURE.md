# Design and acceptance coverage

The service is one stateless Quarkus process with transport, application, immutable domain command, JDBC persistence, security, and observability packages. PostgreSQL is the only source of financial truth. Explicit JDBC transactions were chosen over ORM-managed entities so account locks, idempotency registration, rollback boundaries, and commit ordering remain visible. SQL is parameterized. Neither queues nor distributed transactions are involved.

Strict streaming JSON preserves decimal lexemes, rejects duplicates, unknown properties, nested/type-coerced values and trailing documents. XML uses a restricted flat StAX grammar, disables DTD/external entities, rejects namespaces/attributes/XInclude and duplicate elements, and refuses nested field content. Both feed one validator and command. Amount fingerprints use canonical scale 3, timestamp instants, normalized descriptive text, and length-prefixed SHA-256 input. Accounts are never trimmed. Optional absent/null notes normalize to empty. Format-equivalent instructions therefore share the same fingerprint.

OAuth/OIDC JWT validation is performed by Quarkus SmallRye JWT; an alternative mechanism only customizes the standard 401 representation. Resource name binding applies authorization to every public API route independent of URL spelling. Scopes and ownership/grants are distinct controls. Infrastructure endpoints must be network-restricted. Production transport enforcement defaults on.

The transaction first claims its UUID by primary-key insert. Concurrent duplicate inserts wait for the prior commit. Subsequent reads see the winner's fingerprint/outcome. Both accounts lock individually in ascending identifier order before business validation and arithmetic. A successful transfer has one debit and one credit, matching the same positive amount. A business rejection seals a REJECTED row without ledger entries. Database failures roll back all changes, with bounded retries limited to known transient transaction states. Deferrable database triggers check balanced and matching ledger entries at commit; completed/rejected payment identities and ledger entries cannot be edited or deleted.

The opening balance is an explicit accounting baseline for reconciliation. Administrative provisioning, grants, restrictions, account status changes, backups, and legal retention are operational controls outside the customer API. Production must apply approved provisioning and retention policies, immutable identity mapping, least-privilege roles, TLS configuration, backup scheduling, and monitoring as documented in OPERATIONS.md.

| Acceptance criterion | Automated evidence |
|---|---|
| 1 JSON accounting and ledger | `jsonSuccessReplayAndConflict` checks 374.250 / 225.750, one payment and two ledger entries |
| 2 XML accounting | `xmlSuccessAndCrossFormatReplay` checks canonical receipt and balances |
| 3 malformed structure | `collectsValidationIssuesAndRejectsMalformed`, `mediaAndXmlSecurity` |
| 4 invalid fields | `amountPrecisionAndTimestampRules`, `fieldErrorsIncludeTheDateWindow` |
| 5 insufficient funds | `rejectionIsDurableAndNeverMovesFunds` |
| 6 invalid/inactive accounts | `accountRestrictionsAndHiddenLookups` |
| 7 authorization | Ownership tests plus real signed token scope checks, missing token, bad issuer/audience/expiry/signature |
| 8 replay | JSON replay, cross-format replay, durable rejection replay, process-restart replay |
| 9 payload conflict | `jsonSuccessReplayAndConflict` |
| 10 concurrency | `concurrentDuplicatesHaveExactlyOneEffect`, `concurrentDebitsCannotOverdraw` |
| 11 rollback | `failureAfterBalanceUpdatesRollsBackEverything`: database trigger fails before ledger insert |
| 12 lost-response recovery | `lostResponseAndNewServiceConnectionFindOriginalOutcome`: commit result intentionally discarded before recovery |
| 13 restart durability | `ProcessDurabilityIT` stops the packaged JVM, starts a new JVM on the same PostgreSQL, retrieves/replays exact receipt |
| 14 negotiation | XML/JSON response tests, 415/406 checks |
| 15 XML security | DTD/entity, duplicate, unknown and XInclude rejection tests |
| 16 auditability | Persisted initiator/correlation/timestamps, immutable paired ledger, structured status/code/latency logs |
| 17 reconciliation | Balance corruption and deliberately missing ledger fixture are detected |

Additional tests exercise shared database rate limits with Retry-After, bounded lock timeouts followed by successful retry, cleartext rejection under production policy, body limits, OpenAPI/metrics/health, and privileged reconciliation access.

`mvn verify` also executes a five-second offered-rate probe against the packaged restarted service at 100 POST requests/second, requiring zero errors and p95 below 500 ms. The test is deliberately local and excludes TLS/network topology. Run the longer script on deployment hardware before rollout.
