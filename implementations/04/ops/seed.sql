-- Development fixture only. Run explicitly after Flyway; never load automatically in production.
INSERT INTO customers(id,display_name) VALUES ('alice','Development Alice'),('bob','Development Bob') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status)
VALUES ('ACC-1001','alice','JOD',500.000,500.000,'ACTIVE'),('ACC-2002','bob','JOD',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
