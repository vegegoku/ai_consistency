-- Local development only. Run after Flyway has created the schema.
INSERT INTO customers(id,display_name,status) VALUES ('11111111-1111-1111-1111-111111111111','Alice demo','ACTIVE'),('22222222-2222-2222-2222-222222222222','Bob demo','ACTIVE') ON CONFLICT DO NOTHING;
INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status) VALUES ('ACC-1001','11111111-1111-1111-1111-111111111111','JOD',500.000,500.000,'ACTIVE'),('ACC-2002','22222222-2222-2222-2222-222222222222','JOD',100.000,100.000,'ACTIVE') ON CONFLICT DO NOTHING;
