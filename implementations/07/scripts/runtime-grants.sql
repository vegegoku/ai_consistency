-- Run as schema owner after creating a non-superuser payment_runtime role.
-- Provision its password with the organization's secret manager, outside this file.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_authorizations,payments,ledger_entries TO payment_runtime;
GRANT INSERT ON payments,ledger_entries TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT UPDATE(status,rejection_code,rejection_http,processed_at) ON payments TO payment_runtime;
GRANT USAGE,SELECT ON SEQUENCE ledger_entries_id_seq TO payment_runtime;
-- No DELETE, TRUNCATE, ledger UPDATE, DDL, authorization maintenance or baseline editing rights.
