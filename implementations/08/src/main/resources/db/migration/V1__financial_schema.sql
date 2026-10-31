CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(128) PRIMARY KEY,
 customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 can_receive boolean NOT NULL DEFAULT true,
 transfer_limit numeric(19,3) NOT NULL DEFAULT 10000.000 CHECK(transfer_limit>0),
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_debit_authorizations (
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 principal varchar(128) NOT NULL,
 PRIMARY KEY(account_id,principal)
);
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY,
 from_account_id varchar(128) NOT NULL, to_account_id varchar(128) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 instruction_date timestamptz NOT NULL, purpose varchar(255) NOT NULL, notes varchar(1000) NOT NULL,
 payload_fingerprint char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','REJECTED')),
 rejection_code varchar(64), created_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz,
 requested_by varchar(128) NOT NULL, correlation_id uuid NOT NULL,
 CHECK(from_account_id<>to_account_id),
 CHECK((status='PROCESSING') OR (processed_at IS NOT NULL AND ((status='COMPLETED' AND rejection_code IS NULL) OR (status='REJECTED' AND rejection_code IS NOT NULL))))
);
-- Account identifiers intentionally are not FKs on payments: unknown-account rejections are auditable.
CREATE INDEX payments_from_time ON payments(from_account_id,created_at);
CREATE INDEX payments_to_time ON payments(to_account_id,created_at);
CREATE INDEX payments_principal_time ON payments(requested_by,created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL,
 balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,entry_type),
 CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_time ON ledger_entries(account_id,created_at);
CREATE FUNCTION reject_financial_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Financial records are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION reject_financial_mutation();
CREATE FUNCTION protect_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PROCESSING' THEN
  RAISE EXCEPTION 'Final payment records are immutable' USING ERRCODE='23514';
 END IF;
 IF (NEW.payment_id,NEW.from_account_id,NEW.to_account_id,NEW.amount,NEW.instruction_date,NEW.purpose,NEW.notes,NEW.payload_fingerprint,NEW.requested_by,NEW.correlation_id)
 IS DISTINCT FROM (OLD.payment_id,OLD.from_account_id,OLD.to_account_id,OLD.amount,OLD.instruction_date,OLD.purpose,OLD.notes,OLD.payload_fingerprint,OLD.requested_by,OLD.correlation_id)
 THEN RAISE EXCEPTION 'Payment identity is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_final_payment BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION protect_payment();
CREATE FUNCTION check_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments; n integer; total numeric; matches integer;
BEGIN
 SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE
  (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR
  (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount))
 INTO n,total,matches FROM ledger_entries WHERE payment_id=p.payment_id;
 IF p.status='PROCESSING' OR (p.status='COMPLETED' AND (n<>2 OR total<>0 OR matches<>2)) OR (p.status='REJECTED' AND n<>0)
 THEN RAISE EXCEPTION 'Payment ledger invariant violated' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER complete_payment_ledger AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
CREATE CONSTRAINT TRIGGER validate_ledger_payment AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
