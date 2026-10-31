INSERT INTO customers(id,display_name,status) VALUES ('alice','Alice','ACTIVE'),('bob','Bob','ACTIVE');
INSERT INTO accounts(id,customer_id,opening_balance,available_balance,status) VALUES
 ('ACC-1001','alice',500.000,500.000,'ACTIVE'),('ACC-2002','bob',100.000,100.000,'ACTIVE'),
 ('ACC-FROZEN','alice',100.000,100.000,'FROZEN'),('ACC-CLOSED','bob',100.000,100.000,'CLOSED');
