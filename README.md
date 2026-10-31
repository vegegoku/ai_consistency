# Payment backend implementation comparison

This repository contains 20 independent Java payment backend implementations built from the shared requirements, plus a static comparison report and a JSON/XML payment-message corpus.

## Start here

- [Static source analysis and comparison report](static-source-analysis.html) — source, requirements coverage, security and deployability signals, message-run charts, implementation comparisons, and detailed message outcomes.
- [Payment samples and run results](payment-samples/results/README.md) — the shared ten-account fixture, 250 JSON and 250 XML messages, and sequential processing results for implementations 01–20.
- [Implementation requirements](Payment%20App%20requirements/payment-processing-requirements.md)
- Each implementation has its own [README](implementations/01/README.md) and `AGENTS.md`.

The result corpus includes one JSONL record for each submitted message, a summary, and final account balances per implementation. The common ten-account fixture is reset before each run; balances persist during that implementation's ordered JSON-then-XML processing sequence.

Local deployment credentials and generated build outputs are excluded from Git. Development setup scripts can regenerate local keys; private keys under test resources are test fixtures only.
