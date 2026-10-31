# Payment message samples

This folder contains 250 JSON messages and 250 XML messages for the payment contract in `Payment App requirements/payment-processing-requirements.md`.

## Files

- `accounts.json` contains the fixed set of ten generated JOD test accounts, their opening available balances, states, and sample debit/receive permissions.
- `json/` and `xml/` contain one raw request body per file. File numbers match across formats.
- `manifest.csv` maps each sample number to both request files and the expected outcome/code for each format.
- `generate_samples.py` regenerates the account fixture, all 500 messages, and the manifest deterministically.

Run `python3 payment-samples/generate_samples.py` from the repository root to regenerate the corpus. Each payment sample is intended to run against a reset copy of the account fixture. The idempotency cases must run as a group in the listed order, and concurrency behavior requires parallel submission of the designated duplicate requests. Expected status codes follow the requirements; implementations may differ in exact safe error wording.

## Coverage represented

The corpus includes successful transfers and amount/date boundaries; missing, null, blank, malformed, and wrong-type fields; UUID, account, amount precision, positivity, maximum, and same-account validation; purpose and notes length boundaries; offset-aware dates and past/future limits; unknown, frozen, closed, insufficient-funds, debit-restricted, and receive-restricted account cases; duplicate JSON properties and XML elements; malformed documents, wrong roots, unknown fields/elements, oversized bodies, XML DTD/XXE/XInclude and excessive nesting probes; aggregated field failures; and idempotent replay/payload conflict examples.

Some behaviors depend on the HTTP harness rather than the message body. Authentication, authorization principal setup, request/response media negotiation, rate limiting, and transient database failures must be supplied by the caller or a test harness. XML-specific security probes have valid JSON counterparts because those parser threats are XML-specific, and JSON syntax probes have valid XML counterparts. These are representative cases for each validation rule and boundary; the set cannot enumerate every possible invalid byte sequence or input value.

The account fixture is portable sample data, not a migration for any one of the twenty implementations. Load its records into the implementation under test using that implementation's local fixture/seed mechanism, mapping the permission fields as needed.
