CREATE TABLE customers (
 id varchar(128) PRIMARY KEY, display_name varchar(255) NOT NULL, subject varchar(255) NOT NULL UNIQUE,
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','BLOCKED')), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE accounts (
 id varchar(128) PRIMARY KEY, customer_id varchar(128) NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
 currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'), available_balance numeric(19,3) NOT NULL CHECK(available_balance>=0),
 opening_balance numeric(19,3) NOT NULL CHECK(opening_balance>=0), status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 receive_enabled boolean NOT NULL DEFAULT true, transfer_enabled boolean NOT NULL DEFAULT true, unrestricted_destinations boolean NOT NULL DEFAULT true,
 transfer_limit numeric(19,3) NOT NULL DEFAULT 10000.000 CHECK(transfer_limit>0), daily_limit numeric(19,3) CHECK(daily_limit>0),
 version bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_debit_authorizations (
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT, subject varchar(255) NOT NULL,
 PRIMARY KEY(account_id,subject)
);
CREATE TABLE account_destinations (
 from_account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
 to_account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT, PRIMARY KEY(from_account_id,to_account_id)
);
-- Business-rejected instructions may reference nonexistent accounts, so account references here are intentional snapshots, not FKs.
CREATE TABLE payments (
 payment_id uuid PRIMARY KEY,from_account_id varchar(128) NOT NULL,to_account_id varchar(128) NOT NULL,
 amount numeric(19,3) NOT NULL CHECK(amount>0),currency char(3) NOT NULL DEFAULT 'JOD' CHECK(currency='JOD'),instruction_date timestamptz NOT NULL,
 purpose varchar(255) NOT NULL,notes varchar(1000),payload_fingerprint char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','REJECTED')),rejection_code varchar(64),
 created_at timestamptz NOT NULL DEFAULT now(),processed_at timestamptz,requested_by varchar(255) NOT NULL,
 correlation_id uuid NOT NULL,http_status integer,receipt jsonb,
 CHECK(from_account_id<>to_account_id),CHECK((status='PROCESSING') OR (processed_at IS NOT NULL AND receipt IS NOT NULL AND http_status IS NOT NULL))
);
CREATE INDEX payments_source_time ON payments(from_account_id,processed_at);
CREATE INDEX payments_destination_time ON payments(to_account_id,processed_at);
CREATE INDEX payments_principal_time ON payments(requested_by,created_at);
CREATE TABLE ledger_entries (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,payment_id uuid NOT NULL REFERENCES payments(payment_id) ON DELETE RESTRICT,
 account_id varchar(128) NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,entry_type varchar(6) NOT NULL CHECK(entry_type IN ('DEBIT','CREDIT')),
 signed_amount numeric(19,3) NOT NULL,balance_after numeric(19,3) NOT NULL CHECK(balance_after>=0),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(payment_id,entry_type),CHECK((entry_type='DEBIT' AND signed_amount<0) OR (entry_type='CREDIT' AND signed_amount>0))
);
CREATE INDEX ledger_account_time ON ledger_entries(account_id,created_at);
CREATE TABLE payment_issues (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,payment_id uuid REFERENCES payments(payment_id) ON DELETE RESTRICT,
 code varchar(64) NOT NULL,field varchar(64),safe_message varchar(255) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),correlation_id uuid NOT NULL
);
CREATE INDEX payment_issues_created ON payment_issues(created_at);
