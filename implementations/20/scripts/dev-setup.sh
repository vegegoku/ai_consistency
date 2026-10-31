#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p deploy/tls deploy/jwt
umask 077
if [[ ! -f deploy/jwt/private.pem ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out deploy/jwt/private.pem 2>/dev/null
fi
openssl pkey -in deploy/jwt/private.pem -pubout -out deploy/jwt/public.pem
if [[ ! -f deploy/tls/key.pem ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout deploy/tls/key.pem -out deploy/tls/cert.pem -days 30 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' 2>/dev/null
fi
# Development-only TLS private key must be readable by the unprivileged container.
chmod 644 deploy/tls/key.pem deploy/tls/cert.pem deploy/jwt/public.pem
printf 'Development keys are ready. Run docker compose up --build -d.\n'
