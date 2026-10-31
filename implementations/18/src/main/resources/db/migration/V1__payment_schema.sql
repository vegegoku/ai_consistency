CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL,
 status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(128) PRIMARY KEY, customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0),
 status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 can_receive boolean NOT NULL DEFAULT true, max_payment numeric(19,3) NOT NULL DEFAULT 10000.000 CHECK(max_payment>0),
 daily_debit_limit numeric(19,3) NOT NULL DEFAULT 50000.000 CHECK(daily_debit_limit>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_grants (
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 principal varchar(128) NOT NULL, can_debit boolean NOT NULL DEFAULT false, can_view boolean NOT NULL DEFAULT true,
 PRIMARY KEY(account_id,principal)
);
-- Account identifiers intentionally have no FK here: business rejections must retain unknown destinations.
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY, from_account_id varchar(128) NOT NULL, to_account_id varchar(128) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 instruction_date timestamptz NOT NULL, purpose varchar(255) NOT NULL, notes varchar(1000) NOT NULL DEFAULT '',
 payload_fingerprint char(64) NOT NULL, status varchar(12) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','REJECTED')),
 rejection_code varchar(64), created_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz,
 requested_by varchar(128) NOT NULL, correlation_id uuid NOT NULL,
 CHECK(from_account_id<>to_account_id), CHECK((status='REJECTED')=(rejection_code IS NOT NULL))
);
CREATE INDEX payments_source_date ON payments(from_account_id,processed_at);
CREATE INDEX payments_destination_date ON payments(to_account_id,processed_at);
CREATE INDEX payments_requester_date ON payments(requested_by,created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL, balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(payment_id,entry_type),
 CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_date ON ledger_entries(account_id,created_at);
CREATE TABLE payment_issues (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 code varchar(64) NOT NULL, field varchar(64), safe_message varchar(255) NOT NULL,
 correlation_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE rate_buckets (principal varchar(128) PRIMARY KEY, bucket bigint NOT NULL, requests integer NOT NULL);
CREATE FUNCTION immutable_financial_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'financial records are immutable'; END $$;
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER immutable_issues BEFORE UPDATE OR DELETE ON payment_issues FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE FUNCTION protect_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status IN ('COMPLETED','REJECTED') THEN RAISE EXCEPTION 'payment is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION protect_payment();
CREATE FUNCTION verify_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; n integer; total numeric; good integer;
BEGIN
 SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE
  (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR
  (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount))
 INTO n,total,good FROM ledger_entries WHERE payment_id=p.payment_id;
 IF p.status='PROCESSING' OR (p.status='COMPLETED' AND (n<>2 OR total<>0 OR good<>2)) OR (p.status='REJECTED' AND n<>0)
 THEN RAISE EXCEPTION 'payment ledger invariant violated'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER payment_ledger_check AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
CREATE CONSTRAINT TRIGGER ledger_payment_check AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
