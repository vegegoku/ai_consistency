# Payment message run results

Each implementation has one JSONL record per submitted message, a status-count summary, and a final account balance snapshot. The initial ten-account fixture is reset before each implementation run. Within a run, JSON then XML are submitted for sample 001, then JSON and XML for sample 002, through sample 250; balances persist after every processed message.

## Files

- `implementation-NN.jsonl`: one result per message, in processing order, including HTTP outcome, payment status/code, elapsed time, and any transport error.
- `implementation-NN-summary.json`: totals and status/error code counts.
- `implementation-NN-final-balances.json`: ending balances and account statuses. A few implementations also report an extra account created by their own development seed; the ten fixture account rows are included in every snapshot.
- `run-overview.json`: integrity and status overview across all 20 implementations.

## Processing note

All 20 implementations recorded 500 message attempts each (10,000 total). Implementations 12 and 13 each recorded two transport timeouts for the empty-body probes; the runner recorded those failures and continued through the remaining messages. See their JSONL files for those rows.
