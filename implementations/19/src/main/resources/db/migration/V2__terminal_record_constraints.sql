ALTER TABLE payments ADD CONSTRAINT payment_accounts_differ CHECK(from_account_id<>to_account_id);
ALTER TABLE payments ADD CONSTRAINT payment_final_fields CHECK(status='PROCESSING' OR (processed_at IS NOT NULL AND ((status='COMPLETED' AND rejection_code IS NULL AND rejection_http IS NULL) OR (status='REJECTED' AND rejection_code IS NOT NULL AND rejection_http BETWEEN 400 AND 499))));
