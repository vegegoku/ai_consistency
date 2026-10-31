-- Execute as schema owner with a provisioned login role payment_runtime.
-- Flyway migrations use a separate owner identity in production.
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_permissions,payments,ledger_entries TO payment_runtime;
GRANT INSERT ON payments,ledger_entries TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT UPDATE(status,rejection_code,rejection_http,processed_at) ON payments TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
-- No DELETE, TRUNCATE, ALTER, CREATE, account provisioning, or ledger UPDATE grants.
