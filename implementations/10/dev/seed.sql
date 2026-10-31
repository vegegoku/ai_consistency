-- Explicit local development opt-in only; never executed by production migrations.
INSERT INTO customers(id,display_name,status) VALUES('alice','Local Alice','ACTIVE'),('bob','Local Bob','ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES('ACC-1001','alice',500.000,500.000,'ACTIVE'),('ACC-2002','bob',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
