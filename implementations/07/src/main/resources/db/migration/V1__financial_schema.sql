CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(64) PRIMARY KEY, customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0), opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 debit_enabled boolean NOT NULL DEFAULT true, receive_enabled boolean NOT NULL DEFAULT true,
 max_single_payment numeric(19,3) NOT NULL DEFAULT 10000.000 CHECK(max_single_payment>0),
 daily_limit numeric(19,3) NOT NULL DEFAULT 100000.000 CHECK(daily_limit>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_authorizations (
 account_id varchar(64) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT, principal varchar(128) NOT NULL,
 can_debit boolean NOT NULL DEFAULT false, can_view boolean NOT NULL DEFAULT false, PRIMARY KEY(account_id,principal)
);
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY, from_account_id varchar(64) NOT NULL, to_account_id varchar(64) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency char(3) NOT NULL CHECK(currency='JOD'),
 instruction_date timestamptz NOT NULL, purpose varchar(255) NOT NULL, notes varchar(1000), payload_fingerprint char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','COMPLETED','REJECTED')), rejection_code varchar(64), rejection_http integer,
 requested_by varchar(128) NOT NULL, correlation_id varchar(128) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), processed_at timestamptz,
 CHECK(from_account_id<>to_account_id), CHECK((status='PENDING' AND processed_at IS NULL) OR (status<>'PENDING' AND processed_at IS NOT NULL))
);
-- Account IDs on rejected instructions intentionally have no FK: nonexistent-account rejections are durable.
CREATE INDEX payments_from_time ON payments(from_account_id,processed_at);
CREATE INDEX payments_to_time ON payments(to_account_id,processed_at);
CREATE INDEX payments_initiator ON payments(requested_by,created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(64) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL, balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,entry_type), CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_time ON ledger_entries(account_id,created_at);
CREATE FUNCTION immutable_financial_record() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Financial record is immutable'; END $$;
CREATE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_financial_record();
CREATE FUNCTION guard_payment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PENDING' THEN RAISE EXCEPTION 'Final payment is immutable'; END IF;
 IF (NEW.payment_id,NEW.from_account_id,NEW.to_account_id,NEW.amount,NEW.instruction_date,NEW.purpose,NEW.notes,NEW.payload_fingerprint,NEW.requested_by,NEW.correlation_id) IS DISTINCT FROM
 (OLD.payment_id,OLD.from_account_id,OLD.to_account_id,OLD.amount,OLD.instruction_date,OLD.purpose,OLD.notes,OLD.payload_fingerprint,OLD.requested_by,OLD.correlation_id) THEN RAISE EXCEPTION 'Payment identity is immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER payment_immutable BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION guard_payment();
CREATE FUNCTION guard_account_baseline() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.opening_balance<>OLD.opening_balance OR NEW.customer_id<>OLD.customer_id OR NEW.currency<>OLD.currency OR NEW.id<>OLD.id THEN RAISE EXCEPTION 'Account identity/baseline is immutable'; END IF; RETURN NEW; END $$;
CREATE TRIGGER account_baseline_immutable BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION guard_account_baseline();
CREATE FUNCTION check_complete_ledger() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE n integer; total numeric; matching integer; BEGIN
 IF NEW.status='COMPLETED' THEN
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER (WHERE (entry_type='DEBIT' AND account_id=NEW.from_account_id AND signed_amount=-NEW.amount) OR (entry_type='CREDIT' AND account_id=NEW.to_account_id AND signed_amount=NEW.amount)) INTO n,total,matching FROM ledger_entries WHERE payment_id=NEW.payment_id;
 IF n<>2 OR total<>0 OR matching<>2 THEN RAISE EXCEPTION 'Completed payment requires matching balanced ledger'; END IF;
 END IF; RETURN NEW; END $$;
CREATE CONSTRAINT TRIGGER payment_ledger_consistent AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_complete_ledger();
