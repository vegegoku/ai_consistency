-- Execute as DBA after Flyway migrations. Create payment_runtime login separately
-- through your secret-management system; never commit its password.
GRANT USAGE ON SCHEMA public TO payment_runtime;
GRANT SELECT ON customers,accounts,account_debit_grants,payments,ledger_entries TO payment_runtime;
GRANT INSERT ON payments,ledger_entries TO payment_runtime;
GRANT UPDATE (available_balance,version,updated_at) ON accounts TO payment_runtime;
GRANT UPDATE (status,rejection_code,processed_at) ON payments TO payment_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO payment_runtime;
-- No DELETE, TRUNCATE, DDL, baseline updates, ledger updates or customer/grant administration.
