-- Run as schema owner; provision payments_runtime and its password through your secret manager first.
GRANT CONNECT ON DATABASE payments TO payments_runtime;
GRANT USAGE ON SCHEMA public TO payments_runtime;
GRANT SELECT ON customers,accounts,account_debit_authorizations,account_destinations,payments,ledger_entries,payment_issues TO payments_runtime;
GRANT INSERT ON payments,ledger_entries,payment_issues TO payments_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payments_runtime;
GRANT UPDATE(status,rejection_code,http_status,receipt,processed_at) ON payments TO payments_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payments_runtime;
-- No deletes, DDL, opening-balance updates, ledger updates, grant provisioning, or policy changes.
