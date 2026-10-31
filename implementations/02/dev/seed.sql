-- Development only. Run after Flyway; never load into production.
INSERT INTO customers(id,display_name,status) VALUES ('alice','Local Alice','ACTIVE'),('bob','Local Bob','ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)
VALUES ('ACC-1001','alice','JOD',500.000,500.000,'ACTIVE'),('ACC-2002','bob','JOD',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
