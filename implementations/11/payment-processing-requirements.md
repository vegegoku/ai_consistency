# Payment Processing Backend — Requirements Specification

**Document version:** 1.0  
**Status:** Proposed implementation baseline  
**Application type:** Java backend, Maven multi-module optional (single service recommended initially)

## 1. Objective

Build a secure backend service that receives payment instructions in **JSON or XML**, validates them, transfers funds from a source customer account to a destination customer account, persists the result, and reports any processing or validation issues. Transfers must not create or destroy money, and a retry of the same instruction must not move funds twice.

## 2. Recommended Technology Stack

| Area | Recommendation |
| --- | --- |
| Language | Java 21 LTS |
| Build | Maven |
| Backend framework | Quarkus 3.x |
| HTTP API | Quarkus REST with Jakarta REST |
| JSON | Jackson |
| XML | Jackson XML with secure XML parser settings |
| Database | PostgreSQL |
| Persistence | Hibernate ORM with Panache or JPA; favor explicit SQL/locking for transfers |
| Database migrations | Flyway |
| Validation | Jakarta Bean Validation plus domain-specific validators |
| Tests | JUnit 5, REST Assured, Testcontainers (PostgreSQL) |
| Observability | Structured JSON logging, Micrometer metrics, OpenTelemetry tracing |
| API documentation | SmallRye OpenAPI |

Use a single deployable application initially, organized into API, application, domain, and persistence packages. Avoid distributed transactions or asynchronous balance updates for the initial release.

## 3. Scope and Assumptions

- A payment is an **internal transfer** between two customer accounts in the same system and currency.
- All monetary amounts are expressed in **JOD**, with a scale of **3 decimal places**. Currency is fixed by the system for this version; the incoming message does not need a currency field.
- Account balances represent available settled funds; holds, overdraft facilities, foreign exchange, fees, scheduled transfers, and interbank settlement are outside the initial scope.
- Each account has a unique identifier and belongs to a customer. An account can be active, frozen, or closed.
- Every payment must have a unique `paymentId` to enable safe retries. This is an **additional required field** beyond the six requested business fields.
- `date` means the **payment instruction timestamp** (ISO 8601 with UTC offset). Store it as an instant; processing timestamps are tracked separately.
- `purpose` is required descriptive text; `notes` is optional descriptive text.
- Balances cannot become negative. No overdrafts are permitted.
- Only authenticated, authorized callers may submit payments or retrieve payment results.

## 4. Payment Message Contract

### 4.1 Fields

| Field | Type | Required | Rules |
| --- | --- | --- | --- |
| `paymentId` | String | Yes | Client-generated UUID; unique per logical payment; used for idempotency |
| `fromAccount` | String | Yes | Nonblank; must identify an existing active account |
| `toAccount` | String | Yes | Nonblank; must identify an existing active account; differs from source |
| `amount` | Decimal | Yes | Strictly greater than zero; up to 3 fractional digits; within configured maximum |
| `date` | ISO 8601 datetime | Yes | Offset required; not excessively future-dated or too old |
| `purpose` | String | Yes | Nonblank; max 255 characters |
| `notes` | String | No | Max 1,000 characters; empty value permitted |

**Important:** Monetary amounts must be parsed as `BigDecimal`, never `float` or `double`. JSON clients should send `amount` as a decimal number or decimal string; the service must not silently round excess decimal places. Account identifiers and the payment ID are strings, not numeric values.

### 4.2 JSON example

```json
{
  "paymentId": "d92192df-f79a-4553-8120-5660d2eb1294",
  "fromAccount": "ACC-1001",
  "toAccount": "ACC-2002",
  "amount": 125.750,
  "date": "2026-10-28T13:30:00+03:00",
  "purpose": "Invoice payment INV-2048",
  "notes": "October services"
}
```

### 4.3 XML example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<payment>
    <paymentId>d92192df-f79a-4553-8120-5660d2eb1294</paymentId>
    <fromAccount>ACC-1001</fromAccount>
    <toAccount>ACC-2002</toAccount>
    <amount>125.750</amount>
    <date>2026-10-28T13:30:00+03:00</date>
    <purpose>Invoice payment INV-2048</purpose>
    <notes>October services</notes>
</payment>
```

Both formats must map to **one shared domain command** and pass through the same validation and processing path. Reject unknown fields/elements and ambiguous or duplicate JSON keys/XML elements. Do not silently coerce invalid values.

## 5. HTTP API Requirements

### 5.1 Submit a payment

`POST /api/v1/payments`

- Accept `Content-Type: application/json` and `application/xml`.
- Use the corresponding deserializer based on the declared content type.
- Return JSON responses by default. Clients can request XML responses using `Accept: application/xml`; unsupported response formats return HTTP `406`.
- Reject unsupported request media types with HTTP `415`.
- Require authenticated caller permissions to debit `fromAccount` and submit payments to the specified destination. Never accept account ownership solely because an account ID appears in a request.
- Process synchronously and respond only after the database transaction commits or fails.

**Success:** `201 Created` for the first successful processing, with `Location: /api/v1/payments/{paymentId}` and the receipt body:

```json
{
  "paymentId": "d92192df-f79a-4553-8120-5660d2eb1294",
  "status": "COMPLETED",
  "fromAccount": "ACC-1001",
  "toAccount": "ACC-2002",
  "amount": "125.750",
  "currency": "JOD",
  "processedAt": "2026-10-28T10:30:01Z"
}
```

The server returns a canonical amount string with exactly three decimal places, including for XML output.

### 5.2 Retrieve a payment

`GET /api/v1/payments/{paymentId}`

- Return the stored payment status and receipt if the caller is permitted to see it.
- Return `404` for an unknown payment or one whose existence must not be disclosed to the caller.
- Support `COMPLETED` and `REJECTED` outcomes; clients retrying after a timeout can use this endpoint to determine the result.

### 5.3 Account balance inquiry (authorized)

`GET /api/v1/accounts/{accountId}/balance`

- Return the current persisted available balance and currency to authorized callers.
- This API is useful for testing and operational reconciliation but must not disclose balances to unrelated customers.

## 6. Validation Requirements

Validation occurs in stages. No balances may change unless **all** validation succeeds.

### 6.1 Message validation

1. Request body is present and within a configurable size limit (default 64 KiB).
2. Content type is supported and the document is well-formed JSON or XML.
3. XML parser prohibits DTD, external entities, XInclude, and external resource access (XXE prevention); apply parsing depth and size limits.
4. All required fields are present, of correct types, and satisfy length/format constraints.
5. `paymentId` is a valid UUID.
6. `amount` is positive, at most 3 fractional digits, and no more than the configurable per-payment maximum (default **10,000.000 JOD**).
7. `date` is a valid offset-aware timestamp; it may be at most 5 minutes in the future and at most 30 days old (configurable).
8. Source and destination account IDs differ.
9. Normalize only documented safe formatting (such as trimming purpose and notes); never alter an account identifier, amount, or payment ID silently.

### 6.2 Business validation

1. Both accounts exist.
2. Source account belongs to, or is explicitly debit-authorized for, the authenticated caller.
3. Destination is allowed to receive funds.
4. Both accounts are in a state permitting the transfer (`ACTIVE`).
5. Both accounts use the configured currency `JOD`.
6. Source account has sufficient available funds **at the moment of atomic execution**, not merely during a pre-check.
7. `paymentId` has not been used for a different instruction.
8. Operational transfer limits and any configured account restrictions are satisfied.

Collect all safe-to-report **field-level** input validation failures in one response. For account, authorization, balance, and concurrency checks, report a consistent business error without leaking protected account information.

## 7. Processing Workflow

1. Authenticate the caller and enforce transport security.
2. Parse JSON/XML into a transport DTO.
3. Validate its structure and field values.
4. Convert to a canonical immutable payment command, and compute a stable fingerprint from the normalized business fields.
5. Start one database transaction.
6. Register or locate the `paymentId` under a **database unique constraint** (idempotency guard). If it exists, compare the stored fingerprint and follow the retry rules in Section 8.
7. Load and lock both affected account rows in deterministic order (e.g., ascending account ID), regardless of debit/credit direction.
8. Validate account status, authorization restrictions and current sufficient funds under lock.
9. Debit the source account by the amount and credit the destination account by the exact same amount.
10. Persist a `COMPLETED` payment record and two immutable ledger entries (one debit and one credit), all in the same transaction.
11. Commit the transaction.
12. Return the stored payment receipt. Emit metrics/logs after commit; failures in notifications or telemetry must not undo a committed payment.

**Invariant:** For each completed payment, `debit.amount + credit.amount = 0` in a signed ledger representation, both entries reference the same payment ID, and `sourceBalanceAfter + destinationBalanceAfter = sourceBalanceBefore + destinationBalanceBefore`.

If any operation from steps 6–10 fails, the database transaction must roll back completely: **no partial debit, credit, ledger record, or completed status is permitted**.

## 8. Idempotency, Retries, and Concurrency

- Enforce a **unique index** on `payments.payment_id`. Application-side checks alone are not sufficient.
- A second request with the same `paymentId` and the **same canonical payload**, after the first has completed, returns `200 OK` with the **original receipt**, without adjusting balances again.
- A repeated `paymentId` with a **different payload** returns `409 Conflict` (`IDEMPOTENCY_CONFLICT`), and does not adjust balances.
- Two identical requests that arrive concurrently must result in **exactly one transfer**. The second must wait for or safely resolve the winner's database result and return the recorded outcome; use bounded waits and retryable errors when required.
- For a business-rejected payment that has been durably recorded, repeating the same payment ID returns the original rejection; corrected instructions require a new payment ID.
- Transient infrastructure failures must not be permanently recorded as business rejections. If commit outcome is unknown to the caller, retry with the same ID or query status.
- Lock accounts in a deterministic order to reduce deadlocks. Use database transactions and row locking (or proven equivalent optimistic concurrency with retries) to prevent overdrafts and lost updates.
- Enforce bounded DB lock timeouts and bounded retries for transient deadlocks/serialization conflicts; never retry a transfer with a new payment ID automatically.

## 9. Payment and Error Statuses

| Status | Meaning |
| --- | --- |
| `COMPLETED` | Funds were debited/credited and ledger entries committed |
| `REJECTED` | A validly identified payment was refused for a business reason; no funds moved |

Malformed requests that cannot establish a valid payment identity may return errors without persisting a payment record.

### 9.1 Standard error contract

Return errors consistently in a JSON object (or equivalent XML when negotiated):

```json
{
  "paymentId": "d92192df-f79a-4553-8120-5660d2eb1294",
  "status": "REJECTED",
  "code": "INSUFFICIENT_FUNDS",
  "message": "The payment could not be completed.",
  "issues": [
    {
      "field": "amount",
      "code": "INSUFFICIENT_FUNDS",
      "message": "Available funds are insufficient."
    }
  ],
  "correlationId": "84b2e7b7-df46-4a7e-aa50-87352a37fd4f",
  "timestamp": "2026-10-28T10:30:01Z"
}
```

Omit `paymentId` when no valid payment ID could be parsed. For HTTP errors caused by authentication or malformed documents, `status` may be `ERROR` rather than a persisted payment state. Avoid exposing sensitive balances, customer details, SQL text, or stack traces.

### 9.2 Required error taxonomy

| HTTP | Code | Situation |
| --- | --- | --- |
| 400 | `MALFORMED_MESSAGE` | Invalid JSON/XML, duplicate properties/elements, invalid typed value |
| 400 | `VALIDATION_FAILED` | Missing field, invalid UUID/date, excessive precision, invalid amount, same account |
| 401 | `UNAUTHENTICATED` | Missing or invalid authentication |
| 403 | `FORBIDDEN` | Caller lacks permission to perform the transfer |
| 404 | `ACCOUNT_NOT_FOUND` | Account does not exist (only when safe to disclose) |
| 409 | `ACCOUNT_NOT_ACTIVE` | Frozen/closed or otherwise blocked account |
| 409 | `INSUFFICIENT_FUNDS` | Source balance is too low |
| 409 | `IDEMPOTENCY_CONFLICT` | Same payment ID but different instruction |
| 409 | `PAYMENT_LIMIT_EXCEEDED` | Policy maximum reached |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request is not JSON or XML |
| 429 | `RATE_LIMITED` | Request volume exceeds allowed threshold |
| 503 | `TEMPORARILY_UNAVAILABLE` | Database unavailable, lock timeout, or exhausted transient retries |
| 500 | `INTERNAL_ERROR` | Unanticipated server error |

Use the appropriate HTTP status and `Retry-After` where useful. The API must distinguish definitive rejection from a transient error where the processing outcome should be queried or retried using the same `paymentId`.

## 10. Persistence Requirements

Minimum schema (names illustrative):

### `customers`
- `id` (PK), `display_name`, `status`, `created_at`.

### `accounts`
- `id` (PK, account identifier), `customer_id` (FK), `currency` (`JOD`), `available_balance` (`NUMERIC(19,3)`), `status`, `version`, `created_at`, `updated_at`.
- Database `CHECK (available_balance >= 0)`; no floating-point monetary columns.

### `payments`
- `payment_id` (PK/unique UUID), `from_account_id`, `to_account_id`, `amount` (`NUMERIC(19,3)`), `currency`, `instruction_date`, `purpose`, `notes`, `payload_fingerprint`, `status`, `rejection_code` (nullable), `created_at`, `processed_at` (nullable), `requested_by`.
- Immutable identity and business fields after completion or rejection. Index accounts and timestamps for authorized queries.

### `ledger_entries`
- `id` (PK), `payment_id` (FK), `account_id` (FK), `entry_type` (`DEBIT`/`CREDIT`), `signed_amount` (`NUMERIC(19,3)`), `balance_after` (`NUMERIC(19,3)`), `created_at`.
- Append-only for completed transfers. Unique `(payment_id, entry_type)` and database checks for sign consistency. Each completed transfer must have exactly two entries, enforced by the transactional service and reconciliation checks.

### `payment_issues` (optional but recommended)
- `id`, `payment_id` (nullable for malformed input), `code`, `field`, `safe_message`, `created_at`, `correlation_id`.
- Never store raw secrets or uncontrolled user payloads. Logging and issue retention must be configurable.

All schema changes must run through versioned Flyway migrations. Store timestamps in UTC. Explicitly define foreign-key and deletion policies; transactional records must not be physically deleted through ordinary application flows.

## 11. Security and Audit Requirements

- Require HTTPS/TLS in deployed environments.
- Authenticate service callers with OAuth 2.0/OIDC bearer JWTs; validate issuer, audience, signature, expiration, and scopes.
- Authorize debit operations per customer/account and restrict privileged administrative endpoints.
- Restrict account and payment lookups to authorized principals; use non-enumerable responses where necessary.
- Enforce request body limits, XML parser hardening, rate limits, connection and statement timeouts.
- Prevent SQL injection using parameterized queries and validated identifiers.
- Never log access tokens, credentials, full payloads, or sensitive account details. Include `correlationId`, `paymentId` when valid, status, error code, and latency in structured logs.
- Audit payment receipt/rejection and the initiating principal. Financial ledger records must be immutable and access controlled.
- Use least-privilege database accounts, secret management, regular backups, and restore drills.

## 12. Reliability and Observability

- Expose liveness and readiness health endpoints; readiness fails when required database connectivity is unavailable.
- Publish metrics for request count, completed/rejected count by error code, processing latency, idempotent replays, retries, database lock timeouts, and unexpected failures.
- Propagate a correlation ID from a trusted header or generate one. Return it in responses and logs.
- Provide a reconciliation task/report that identifies any completed payment without exactly two balanced ledger entries and any disagreement between account balances and their ledger-derived state, based on an established opening-balance baseline.
- A committed transfer must remain discoverable after process restart.
- Retain payments, ledger entries, and relevant audit records according to a documented configurable retention policy and applicable legal requirements.

## 13. Nonfunctional Requirements

| Concern | Requirement |
| --- | --- |
| Correctness | Atomic transfer; no overdraft; exactly-once financial effect per payment ID |
| Precision | Decimal arithmetic only; exact JOD scale of 3 |
| Performance | Target p95 under 500 ms for valid payments at 100 requests/second in a reference environment, excluding external network latency; verify by load tests |
| Availability | Designed for stateless application replicas backed by PostgreSQL; DB failure must fail safely, never perform partial transfers |
| Maintainability | Layered architecture, clear exception mapping, documented API, migration-based database changes |
| Portability | Maven build and container deployment; configuration via environment variables |
| Testing | Automated unit, integration, concurrency, security, and failure-injection tests in CI |
| Compatibility | Versioned `/api/v1` endpoints; stable contracts across JSON and XML |

Performance figures are initial engineering targets, not guarantees; they must be measured and tuned on representative hardware.

## 14. Suggested Maven Project Layout

```text
payment-service/
├── pom.xml
├── src/main/java/com/example/payments/
│   ├── api/                 # REST resources, JSON/XML request and response DTOs
│   ├── application/         # Payment orchestration and transactional use cases
│   ├── domain/              # Payment, money, account rules and error codes
│   ├── persistence/         # Repositories and DB entities
│   ├── security/            # Authentication and authorization policies
│   └── observability/       # Logging, metrics, correlation IDs
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/        # Flyway SQL versions
├── src/test/java/          # Unit and API tests
└── README.md
```

Keep transport formats independent from the domain model; both XML and JSON should call the exact same `PaymentService.transfer(...)` use case.

## 15. Acceptance Criteria

1. **JSON success:** A valid JSON transfer of 125.750 JOD from an account with 500.000 to another with 100.000 results in 374.250 and 225.750 respectively, one completed payment, and two corresponding ledger entries.
2. **XML success:** The equivalent valid XML instruction causes the same accounting behavior.
3. **Invalid structure:** Malformed JSON/XML returns `400` and changes no balances.
4. **Invalid fields:** Missing or malformed fields (including negative/zero amount, excess precision, invalid timestamp, same source/destination) return documented issues with no balance changes.
5. **Insufficient funds:** The transfer is rejected and neither account changes.
6. **Invalid account:** Nonexistent, frozen, or closed accounts are rejected without balance changes or unauthorized information disclosure.
7. **Authorization:** A caller without debit permission cannot transfer money from the source account.
8. **Idempotent replay:** The identical request, submitted repeatedly in either format with the same ID and equivalent canonical content, produces exactly one financial transfer and returns the original outcome on subsequent calls.
9. **Payload conflict:** Reusing a payment ID with a different amount or recipient returns `409`, with no second transfer.
10. **Concurrency:** Concurrent requests drawing from the same source cannot overdraw it; concurrent duplicate payment IDs debit/credit only once.
11. **Atomic rollback:** Injected failure after debit but before credit or ledger persistence rolls back every change.
12. **Failure uncertainty:** After a simulated response loss following commit, retrying the same ID returns the original completed receipt without moving funds again.
13. **Restart durability:** Balances, ledger, and payment status remain correct after restarting the service.
14. **Media negotiation:** JSON and XML input are handled consistently; unsupported media types and response types are rejected with `415`/`406`.
15. **XML security:** External entity and DTD payloads are rejected without network or local file access.
16. **Auditability:** Every completed transfer identifies the authenticated initiator, timestamps, linked debit/credit ledger entries, and correlation ID in operational logs.
17. **Reconciliation:** A deliberately corrupted fixture is detected by the reconciliation report.

## 16. Delivery Requirements

The implementation deliverables must include:

- A compilable Maven project and automated CI test command (`mvn verify`).
- REST API implementation for payment submission, status retrieval, and authorized balance inquiry.
- OpenAPI specification including example JSON/XML messages and all error responses.
- Flyway schema migrations and local PostgreSQL setup using Docker Compose.
- Representative seeded customer accounts for local development/tests (never production credentials).
- Unit, integration, race-condition, idempotency, rollback, and XML parser security tests.
- README with setup, environment configuration, example `curl` requests, database migration steps, and troubleshooting instructions.
- Operational documentation for account/ledger reconciliation, backup/restore, and incident handling.

## 17. Definition of Done

The system is ready for the initial release when all acceptance criteria pass in CI against PostgreSQL, both payload formats have identical business behavior, authorization/security checks are in place, failures are reported consistently, and tested concurrency plus fault-injection scenarios demonstrate that no partial or duplicate transfer can occur.
