#!/usr/bin/env sh
set -eu
# JAVA_HOME must select Java 21. Requires Docker, openssl and Python 3.
cd "$(dirname "$0")/.."
mvn -B verify
mkdir -p target/smoke
cleanup() {
  docker stop payment04-smoke >/dev/null 2>&1 || true
  python3 -c "from pathlib import Path; [Path(p).unlink(missing_ok=True) for p in ['target/smoke/private.pem','target/smoke/tls.key']]"
}
trap cleanup EXIT INT TERM
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out target/smoke/private.pem >/dev/null 2>&1
openssl pkey -in target/smoke/private.pem -pubout -out target/smoke/public.pem >/dev/null 2>&1
openssl req -x509 -newkey rsa:2048 -nodes -keyout target/smoke/tls.key -out target/smoke/tls.pem -days 1 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' >/dev/null 2>&1
docker run -d --rm --name payment04-smoke -e POSTGRES_DB=payments -e POSTGRES_USER=payments -e POSTGRES_PASSWORD=local-smoke-only -p 127.0.0.1::5432 postgres:16-alpine > target/smoke/container.txt
docker port payment04-smoke 5432/tcp > target/smoke/db-port.txt
python3 scripts/smoke.py
