-- Run as schema owner after creating a runtime role named payment_runtime.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_permissions,payments,ledger_entries TO payment_runtime;
GRANT INSERT ON payments,ledger_entries TO payment_runtime;
GRANT UPDATE(status,rejection_code,processed_at) ON payments TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT INSERT,UPDATE,SELECT ON rate_buckets TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
