CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(128) PRIMARY KEY, customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL CHECK(currency='JOD'), available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 receive_policy varchar(16) NOT NULL DEFAULT 'PUBLIC' CHECK(receive_policy IN ('PUBLIC','OWNER_ONLY','ALLOWLIST')),
 max_transfer_amount numeric(19,3) CHECK(max_transfer_amount>0), daily_outgoing_limit numeric(19,3) CHECK(daily_outgoing_limit>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX accounts_customer_idx ON accounts(customer_id);
CREATE TABLE account_permissions (
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 principal varchar(128) NOT NULL, can_debit boolean NOT NULL DEFAULT false,
 can_receive boolean NOT NULL DEFAULT false, can_read boolean NOT NULL DEFAULT false,
 PRIMARY KEY(account_id,principal)
);
-- No account foreign keys on rejected instructions: account IDs may not exist.
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY, from_account_id varchar(128) NOT NULL, to_account_id varchar(128) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency char(3) NOT NULL CHECK(currency='JOD'),
 instruction_date timestamptz NOT NULL, purpose varchar(255) NOT NULL, notes varchar(1000) NOT NULL,
 payload_fingerprint char(64) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','REJECTED')),
 rejection_code varchar(64), rejection_http integer, created_at timestamptz NOT NULL DEFAULT now(),
 processed_at timestamptz, requested_by varchar(128) NOT NULL, correlation_id uuid NOT NULL,
 CHECK((status='REJECTED')=(rejection_code IS NOT NULL)),
 CHECK((status='PROCESSING')=(processed_at IS NULL))
);
CREATE INDEX payments_from_time_idx ON payments(from_account_id,processed_at);
CREATE INDEX payments_to_time_idx ON payments(to_account_id,processed_at);
CREATE INDEX payments_principal_idx ON payments(requested_by,created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 entry_type varchar(8) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL, balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(payment_id,entry_type),
 CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_idx ON ledger_entries(account_id,id);
CREATE TABLE payment_issues (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 code varchar(64) NOT NULL, field varchar(64), safe_message varchar(255) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), correlation_id uuid NOT NULL
);
CREATE FUNCTION protect_final_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status IN ('COMPLETED','REJECTED') THEN RAISE EXCEPTION 'Financial records are immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_payment BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION protect_final_payment();
CREATE FUNCTION immutable_financial_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Financial records are append-only' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE TRIGGER immutable_issues BEFORE UPDATE OR DELETE ON payment_issues FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE FUNCTION protect_opening_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF OLD.opening_balance<>NEW.opening_balance OR OLD.currency<>NEW.currency OR OLD.customer_id<>NEW.customer_id THEN RAISE EXCEPTION 'Account baseline and identity are immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER immutable_baseline BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION protect_opening_balance();
CREATE FUNCTION check_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; n integer; total numeric; correct integer;
BEGIN
 SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount)) INTO n,total,correct FROM ledger_entries WHERE payment_id=p.payment_id;
 IF p.status='PROCESSING' OR (p.status='COMPLETED' AND (n<>2 OR total<>0 OR correct<>2)) OR (p.status='REJECTED' AND n<>0) THEN RAISE EXCEPTION 'Payment ledger invariant violation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER payment_balanced AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
CREATE CONSTRAINT TRIGGER ledger_balanced AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
