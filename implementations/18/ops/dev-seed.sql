-- Development only; run explicitly after Flyway migrations. Contains no authentication credentials.
INSERT INTO customers(id,display_name) VALUES('alice','Alice Example'),('bob','Bob Example') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,available_balance,opening_balance)
VALUES('ACC-1001','alice',500.000,500.000),('ACC-2002','bob',100.000,100.000)
ON CONFLICT DO NOTHING;
