-- Development profile only. No credentials or accounts are installed in production.
INSERT INTO customers(id,display_name,status) VALUES ('alice','Local Alice','ACTIVE'),('bob','Local Bob','ACTIVE');
INSERT INTO accounts(id,customer_id,opening_balance,available_balance,status,public_receive) VALUES ('ACC-1001','alice',500.000,500.000,'ACTIVE',true),('ACC-2002','bob',100.000,100.000,'ACTIVE',true);
