-- Local/test fixtures only: run after Flyway has migrated the database.
INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice local fixture','ACTIVE'),('bob','Bob local fixture','ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,opening_balance,available_balance) VALUES ('ACC-1001','alice',500.000,500.000),('ACC-2002','bob',100.000,100.000) ON CONFLICT DO NOTHING;
