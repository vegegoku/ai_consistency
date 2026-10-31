-- Local profile only: no production users, passwords or data.
INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice Demo','ACTIVE'),('bob','Bob Demo','ACTIVE');
INSERT INTO accounts(id,customer_id,available_balance,opening_balance) VALUES ('ACC-1001','alice',500.000,500.000),('ACC-2002','bob',100.000,100.000);
