#!/usr/bin/env python3
"""Reset the ten payment sample accounts and grants in an already migrated DB."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ACCOUNTS = json.loads((ROOT / "accounts.json").read_text(encoding="utf-8"))["accounts"]

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5432)
    p.add_argument("--database", default="payments")
    p.add_argument("--user", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--customer-1001", default="alice")
    p.add_argument("--customer-2002", default="bob")
    p.add_argument("--customer-1010", default="bob")
    args = p.parse_args()

    sql = [
        """DO $$ BEGIN
          IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='customers' AND column_name='subject') THEN
            EXECUTE 'INSERT INTO customers(id,display_name,subject,status) VALUES (''alice'',''Sample owner'',''alice'',''ACTIVE''),(''bob'',''Sample other'',''bob'',''ACTIVE'') ON CONFLICT(id) DO NOTHING';
          ELSE
            EXECUTE 'INSERT INTO customers(id,display_name,status) VALUES (''alice'',''Sample owner'',''ACTIVE''),(''bob'',''Sample other'',''ACTIVE'') ON CONFLICT(id) DO NOTHING';
          END IF;
        END $$;"""
    ]
    owners = {"ACC-1001": args.customer_1001, "ACC-2002": args.customer_2002, "ACC-1010": args.customer_1010}
    for account in ACCOUNTS:
        aid = account["accountId"]
        customer = owners.get(aid, "alice")
        sql.append(
            "INSERT INTO accounts(id,customer_id,currency,available_balance,opening_balance,status) "
            f"VALUES ('{aid}','{customer}','{account['currency']}',{account['availableBalance']},{account['availableBalance']},'{account['status']}') "
            "ON CONFLICT(id) DO NOTHING;"
        )
    sql += [
        """DO $$ DECLARE c record; BEGIN
          FOR c IN SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='accounts'
            AND column_name IN ('receive_enabled','receiving_allowed','receive_allowed','allow_receive','allow_incoming','incoming_allowed','can_receive')
          LOOP EXECUTE format('UPDATE accounts SET %I=false WHERE id=%L', c.column_name, 'ACC-9009'); END LOOP;
        END $$;""",
        """DO $$ DECLARE t text; cols text; vals text; updates text; c text; BEGIN
          FOREACH t IN ARRAY ARRAY['account_permissions','account_permission','account_authorizations','account_grants','account_debit_authorizations'] LOOP
            IF to_regclass('public.'||t) IS NULL THEN CONTINUE; END IF;
            IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='principal') THEN CONTINUE; END IF;
            cols := 'account_id,principal';
            vals := 'a.id, ''alice''';
            updates := '';
            FOREACH c IN ARRAY ARRAY['can_debit','can_receive','can_view','can_read'] LOOP
              IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name=c) THEN
                cols := cols||','||quote_ident(c);
                IF c='can_debit' THEN vals := vals||', (a.id <> ''ACC-1010'')';
                ELSIF c='can_receive' THEN vals := vals||', (a.id <> ''ACC-9009'')';
                ELSE vals := vals||', true'; END IF;
                IF updates<>'' THEN updates := updates||','; END IF;
                updates := updates||quote_ident(c)||'=EXCLUDED.'||quote_ident(c);
              END IF;
            END LOOP;
            IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=t AND column_name='principal') THEN
                EXECUTE format('INSERT INTO %I (%s) SELECT %s FROM accounts a ON CONFLICT DO NOTHING',t,cols,vals);
            END IF;
          END LOOP;
          IF to_regclass('public.debit_grants') IS NOT NULL THEN
            EXECUTE 'INSERT INTO debit_grants(account_id,principal) SELECT id,''alice'' FROM accounts WHERE id<>''ACC-1010'' ON CONFLICT DO NOTHING';
          END IF;
          IF to_regclass('public.account_debit_grants') IS NOT NULL THEN
            IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='account_debit_grants' AND column_name='subject') THEN
              EXECUTE 'INSERT INTO account_debit_grants(account_id,subject) SELECT id,''alice'' FROM accounts WHERE id<>''ACC-1010'' ON CONFLICT DO NOTHING';
            ELSE
              EXECUTE 'INSERT INTO account_debit_grants(account_id,principal) SELECT id,''alice'' FROM accounts WHERE id<>''ACC-1010'' ON CONFLICT DO NOTHING';
            END IF;
          END IF;
          IF to_regclass('public.account_debit_authorizations') IS NOT NULL THEN
            EXECUTE 'INSERT INTO account_debit_authorizations(account_id,subject) SELECT id,''alice'' FROM accounts WHERE id<>''ACC-1010'' ON CONFLICT DO NOTHING';
          END IF;
        END $$;""",
    ]
    env = dict(os.environ, PGPASSWORD=args.password)
    subprocess.run(
        ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-h", args.host, "-p", str(args.port), "-U", args.user, "-d", args.database],
        input="\n".join(sql), text=True, env=env, check=True,
    )
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
