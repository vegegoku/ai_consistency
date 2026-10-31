-- A provisional idempotency guard must never be committed; completed payments must
-- have exactly two correct, balanced entries. Deferred checks see final tx state.
CREATE FUNCTION check_final_payment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; n integer; total numeric; correct integer;
BEGIN
 SELECT * INTO p FROM payments WHERE payment_id=NEW.payment_id;
 IF p.rejection_code='PROCESSING' THEN RAISE EXCEPTION 'Provisional payment cannot commit'; END IF;
 SELECT count(*),coalesce(sum(signed_amount),0),count(*) FILTER(WHERE
 (entry_type='DEBIT' AND account_id=p.from_account_id AND signed_amount=-p.amount) OR
 (entry_type='CREDIT' AND account_id=p.to_account_id AND signed_amount=p.amount))
 INTO n,total,correct FROM ledger_entries WHERE payment_id=p.payment_id;
 IF p.status='COMPLETED' AND (n<>2 OR total<>0 OR correct<>2) THEN RAISE EXCEPTION 'Unbalanced payment'; END IF;
 IF p.status='REJECTED' AND n<>0 THEN RAISE EXCEPTION 'Rejected payment has ledger entries'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER final_payment AFTER INSERT OR UPDATE ON payments
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_final_payment();
CREATE CONSTRAINT TRIGGER final_ledger AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_final_payment();
