CREATE FUNCTION deny_financial_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Final financial records are immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER ledger_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION deny_financial_mutation();
CREATE FUNCTION protect_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PROCESSING' THEN RAISE EXCEPTION 'Final payment records are immutable' USING ERRCODE='23514'; END IF;
 IF (NEW.payment_id,NEW.from_account_id,NEW.to_account_id,NEW.amount,NEW.currency,NEW.instruction_date,NEW.purpose,NEW.notes,NEW.payload_fingerprint,NEW.created_at,NEW.requested_by,NEW.correlation_id)
 IS DISTINCT FROM (OLD.payment_id,OLD.from_account_id,OLD.to_account_id,OLD.amount,OLD.currency,OLD.instruction_date,OLD.purpose,OLD.notes,OLD.payload_fingerprint,OLD.created_at,OLD.requested_by,OLD.correlation_id)
 THEN RAISE EXCEPTION 'Payment business fields are immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_immutable BEFORE UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION protect_payment();
CREATE FUNCTION protect_opening_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.opening_balance<>OLD.opening_balance OR NEW.currency<>OLD.currency OR NEW.id<>OLD.id THEN RAISE EXCEPTION 'Account baseline is immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER account_baseline_immutable BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION protect_opening_balance();
CREATE FUNCTION verify_payment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; n integer; total numeric; valid integer;
BEGIN
 SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
 IF p.status='PROCESSING' THEN RAISE EXCEPTION 'Unfinished payment cannot commit' USING ERRCODE='23514'; END IF;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount)) INTO n,total,valid FROM ledger_entries WHERE payment_id=p.payment_id;
 IF (p.status='COMPLETED' AND (n<>2 OR total<>0 OR valid<>2)) OR (p.status='REJECTED' AND n<>0) THEN RAISE EXCEPTION 'Payment ledger invariant violated' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER payment_commit_guard AFTER INSERT OR UPDATE ON payments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
CREATE CONSTRAINT TRIGGER ledger_commit_guard AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_payment_ledger();
