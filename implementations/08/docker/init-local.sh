#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v app_password="$APP_PASSWORD" <<'SQL'
CREATE ROLE payments_app LOGIN PASSWORD :'app_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
