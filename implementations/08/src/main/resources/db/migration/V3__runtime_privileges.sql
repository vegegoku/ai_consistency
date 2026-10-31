-- Production DBAs provision payments_app through secret management before migration.
-- Migration runs as a separate schema owner; application never needs schema ownership.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='payments_app') THEN
  GRANT USAGE ON SCHEMA public TO payments_app;
  GRANT SELECT ON customers,accounts,account_debit_authorizations,payments,ledger_entries TO payments_app;
  GRANT UPDATE(available_balance,version,updated_at) ON accounts TO payments_app;
  GRANT INSERT ON payments,ledger_entries TO payments_app;
  GRANT UPDATE(status,rejection_code,processed_at) ON payments TO payments_app;
  GRANT SELECT,INSERT,UPDATE ON principal_rate_limits TO payments_app;
  GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payments_app;
 END IF;
END $$;
