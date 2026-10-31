INSERT INTO customers(id,display_name,subject) VALUES('CUSTOMER-ALICE','Alice (development)','dev-alice'),('CUSTOMER-BOB','Bob (development)','dev-bob') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('ACC-1001','CUSTOMER-ALICE',500.000,500.000),('ACC-2002','CUSTOMER-BOB',100.000,100.000) ON CONFLICT DO NOTHING;
