-- Local-only migration; production uses only classpath:db/migration.
INSERT INTO customers(id,display_name,status) VALUES('C1','Alice local fixture','ACTIVE'),('C2','Bob local fixture','ACTIVE');
INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES('ACC-1001','C1',500.000,500.000),('ACC-2002','C2',100.000,100.000);
