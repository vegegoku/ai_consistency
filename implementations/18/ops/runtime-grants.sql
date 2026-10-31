-- Run as DBA after creating a separate LOGIN role payment_runtime through your secret manager.
-- Migrations run with a separate owner/migration role; disable migrate-at-start for runtime replicas.
GRANT CONNECT ON DATABASE payments TO payment_runtime;
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_grants,payments,ledger_entries,payment_issues TO payment_runtime;
GRANT INSERT ON payments,ledger_entries,payment_issues TO payment_runtime;
GRANT UPDATE(status,rejection_code,processed_at) ON payments TO payment_runtime;
GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT SELECT,INSERT,UPDATE ON rate_buckets TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
