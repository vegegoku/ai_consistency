CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','BLOCKED')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(128) PRIMARY KEY, customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency varchar(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0),
 available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 receive_enabled boolean NOT NULL DEFAULT true,
 max_transfer numeric(19,3) NOT NULL DEFAULT 10000.000 CHECK(max_transfer>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_permissions (
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 principal varchar(128) NOT NULL, can_debit boolean NOT NULL DEFAULT false,
 can_view boolean NOT NULL DEFAULT false, PRIMARY KEY(account_id,principal)
);
-- No account FK on instruction identifiers: durable rejections include nonexistent destination IDs.
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY, from_account_id varchar(128) NOT NULL, to_account_id varchar(128) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0), currency varchar(3) NOT NULL CHECK(currency='JOD'),
 instruction_date timestamptz NOT NULL, purpose varchar(255) NOT NULL, notes varchar(1000) NOT NULL,
 payload_fingerprint char(64) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','REJECTED')),
 rejection_code varchar(64), rejection_http smallint, created_at timestamptz NOT NULL DEFAULT now(),
 processed_at timestamptz, requested_by varchar(128) NOT NULL, correlation_id uuid NOT NULL,
 CHECK(from_account_id<>to_account_id),
 CHECK((status='PROCESSING' AND processed_at IS NULL) OR (status IN ('COMPLETED','REJECTED') AND processed_at IS NOT NULL)),
 CHECK((status='REJECTED' AND rejection_code IS NOT NULL AND rejection_http IS NOT NULL) OR (status<>'REJECTED' AND rejection_code IS NULL AND rejection_http IS NULL))
);
CREATE INDEX payments_from_time ON payments(from_account_id,created_at);
CREATE INDEX payments_to_time ON payments(to_account_id,created_at);
CREATE INDEX payments_time ON payments(created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')), signed_amount numeric(19,3) NOT NULL,
 balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,entry_type),
 CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_time ON ledger_entries(account_id,created_at);
CREATE TABLE rate_windows (
 principal varchar(128) NOT NULL, window_start bigint NOT NULL, requests integer NOT NULL CHECK(requests>0),
 PRIMARY KEY(principal,window_start)
);
CREATE FUNCTION guard_ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'ledger entries are append-only'; END $$;
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION guard_ledger_immutable();
CREATE FUNCTION guard_payment_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
  IF TG_OP='DELETE' OR OLD.status<>'PROCESSING' THEN RAISE EXCEPTION 'final payment is immutable'; END IF;
  IF (to_jsonb(OLD)-ARRAY['status','rejection_code','rejection_http','processed_at']) IS DISTINCT FROM
     (to_jsonb(NEW)-ARRAY['status','rejection_code','rejection_http','processed_at']) THEN RAISE EXCEPTION 'payment instruction is immutable'; END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER immutable_payment BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION guard_payment_immutable();
CREATE FUNCTION guard_opening_baseline() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN IF OLD.opening_balance IS DISTINCT FROM NEW.opening_balance OR OLD.id IS DISTINCT FROM NEW.id OR OLD.currency IS DISTINCT FROM NEW.currency THEN RAISE EXCEPTION 'account baseline is immutable'; END IF;RETURN NEW;END $$;
CREATE TRIGGER immutable_baseline BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION guard_opening_baseline();
CREATE FUNCTION check_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE p payments%ROWTYPE; n integer; net numeric; correct integer;
 BEGIN
  SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
  SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE
   (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR
   (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount))
   INTO n,net,correct FROM ledger_entries WHERE payment_id=p.payment_id;
  IF p.status='PROCESSING' OR (p.status='COMPLETED' AND (n<>2 OR net<>0 OR correct<>2)) OR (p.status='REJECTED' AND n<>0)
   THEN RAISE EXCEPTION 'payment ledger invariant violation'; END IF;
  RETURN NULL;
 END $$;
CREATE CONSTRAINT TRIGGER balanced_payment AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
CREATE CONSTRAINT TRIGGER balanced_ledger AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payment_ledger();
