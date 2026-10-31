-- Run as migration owner. Create a LOGIN through your secret manager and grant it this group role.
CREATE ROLE payments_runtime NOLOGIN;
GRANT CONNECT ON DATABASE payments TO payments_runtime;
GRANT USAGE ON SCHEMA public TO payments_runtime;
GRANT SELECT ON customers,accounts,account_debit_grants,payments,ledger_entries TO payments_runtime;
GRANT INSERT ON payments,ledger_entries TO payments_runtime;
GRANT UPDATE (status,rejection_code,rejection_http,processed_at) ON payments TO payments_runtime;
GRANT UPDATE (available_balance,version,updated_at) ON accounts TO payments_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO payments_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
