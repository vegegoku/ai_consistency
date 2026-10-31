-- Run as DBA after Flyway with a separately provisioned login role payment_runtime.
-- Provision password through your secret manager, never this file.
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_debit_grants,payments,ledger_entries TO payment_runtime;
GRANT INSERT ON payments,ledger_entries TO payment_runtime;
GRANT UPDATE(status,rejection_code,http_status,processed_at) ON payments TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT SELECT,INSERT,UPDATE ON request_limits TO payment_runtime;
GRANT USAGE,SELECT ON SEQUENCE ledger_entries_id_seq TO payment_runtime;
REVOKE DELETE,TRUNCATE ON customers,accounts,payments,ledger_entries FROM payment_runtime;
