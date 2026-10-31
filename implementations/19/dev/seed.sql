-- Explicit local development action after Flyway startup. Never execute in production.
INSERT INTO customers(id,display_name,status) VALUES ('alice','Development Alice','ACTIVE'),('bob','Development Bob','ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES ('ACC-1001','alice',500.000,500.000,'ACTIVE'),('ACC-2002','bob',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
