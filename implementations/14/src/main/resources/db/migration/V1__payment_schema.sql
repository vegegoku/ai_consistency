CREATE TABLE customers (
 id varchar(200) PRIMARY KEY, display_name varchar(255) NOT NULL, status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','SUSPENDED')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(100) PRIMARY KEY, customer_id varchar(200) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency varchar(3) NOT NULL CHECK(currency='JOD'), available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0), status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 receive_allowed boolean NOT NULL DEFAULT true, unrestricted_destinations boolean NOT NULL DEFAULT true,
 daily_debit_limit numeric(19,3) NOT NULL DEFAULT 100000.000 CHECK(daily_debit_limit>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_debit_grants(account_id varchar(100) REFERENCES accounts(id) ON DELETE RESTRICT, principal varchar(200) NOT NULL, PRIMARY KEY(account_id,principal));
CREATE TABLE allowed_destinations(from_account_id varchar(100) REFERENCES accounts(id) ON DELETE RESTRICT,to_account_id varchar(100) REFERENCES accounts(id) ON DELETE RESTRICT, PRIMARY KEY(from_account_id,to_account_id));
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY, from_account_id varchar(100) NOT NULL, to_account_id varchar(100) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency varchar(3) NOT NULL CHECK(currency='JOD'), instruction_date timestamptz NOT NULL,
 purpose varchar(255) NOT NULL, notes varchar(1000), payload_fingerprint varchar(64) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('COMPLETED','REJECTED')), rejection_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz NOT NULL, requested_by varchar(200) NOT NULL,
 correlation_id uuid NOT NULL, http_status integer NOT NULL, receipt jsonb NOT NULL,
 CHECK(from_account_id<>to_account_id), CHECK((status='COMPLETED' AND rejection_code IS NULL) OR (status='REJECTED' AND rejection_code IS NOT NULL))
);
CREATE INDEX payments_source_time ON payments(from_account_id,processed_at);
CREATE INDEX payments_destination_time ON payments(to_account_id,processed_at);
CREATE INDEX payments_principal_time ON payments(requested_by,processed_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(100) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT, entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL, balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,entry_type), CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account ON ledger_entries(account_id,id);
CREATE TABLE payment_issues (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,payment_id uuid REFERENCES payments(payment_id) ON DELETE RESTRICT,
 code varchar(80) NOT NULL,field varchar(100) NOT NULL,safe_message varchar(500) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),correlation_id uuid NOT NULL
);
CREATE FUNCTION immutable_financial_record() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Financial records are immutable' USING ERRCODE='23000'; END $$;
CREATE TRIGGER payments_immutable BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE FUNCTION verify_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pid uuid; p payments; n integer; total numeric; good integer;
BEGIN
 pid:=NEW.payment_id; SELECT * INTO p FROM payments WHERE payment_id=pid;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount)) INTO n,total,good FROM ledger_entries WHERE payment_id=pid;
 IF (p.status='COMPLETED' AND (n<>2 OR total<>0 OR good<>2)) OR (p.status='REJECTED' AND n<>0) THEN RAISE EXCEPTION 'Ledger invariant violation' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER completed_ledger AFTER INSERT ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
CREATE CONSTRAINT TRIGGER ledger_balanced AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
