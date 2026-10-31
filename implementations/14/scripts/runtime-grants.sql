-- Run as migration owner after provisioning payments_runtime through secret management.
GRANT CONNECT ON DATABASE payments TO payments_runtime;
GRANT USAGE ON SCHEMA public TO payments_runtime;
GRANT SELECT ON customers,accounts,account_debit_grants,allowed_destinations,payments,ledger_entries,payment_issues TO payments_runtime;
GRANT INSERT ON payments,ledger_entries,payment_issues TO payments_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payments_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payments_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
