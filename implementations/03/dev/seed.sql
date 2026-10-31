-- Run AFTER Flyway schema creation: docker compose exec -T postgres psql -U payments payments < dev/seed.sql
-- This file is intentionally harmless on a fresh empty database: migration owns schema creation.
DO $$ BEGIN
IF to_regclass('public.accounts') IS NOT NULL THEN
INSERT INTO customers(id,display_name) VALUES ('alice','Alice development'),('bob','Bob development') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status) VALUES ('ACC-1001','alice','JOD',500.000,500.000,'ACTIVE'),('ACC-2002','bob','JOD',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO account_permissions(account_id,principal,can_receive) VALUES ('ACC-2002','alice',true) ON CONFLICT DO NOTHING;
END IF;
END $$;
