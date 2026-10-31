\set ON_ERROR_STOP on
BEGIN;
DELETE FROM payment_issues
WHERE created_at < now() - make_interval(days => :'retention_days'::integer);
COMMIT;
