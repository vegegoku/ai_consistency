#!/usr/bin/env sh
set -eu
: "${PGHOST:?Set PGHOST}" "${PGUSER:?Set PGUSER}" "${PGDATABASE:?Set PGDATABASE}" "${PGPASSFILE:?Use a chmod 600 pgpass secret}"
umask 077
pg_dump --format=custom --no-owner --file="${1:-payments-backup.dump}"
