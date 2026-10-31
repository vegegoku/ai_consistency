#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Select a Java 21 JDK}"
export DB_URL=${DB_URL:-jdbc:postgresql://localhost:55420/payments}
export DB_USER=${DB_USER:-payments}
export DB_PASSWORD=${DB_PASSWORD:-local-development-only}
export JWT_PUBLIC_KEY="file:$PWD/deploy/jwt/public.pem"
export QUARKUS_HTTP_SSL_CERTIFICATE_FILES="$PWD/deploy/tls/cert.pem"
export QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES="$PWD/deploy/tls/key.pem"
export QUARKUS_HTTP_SSL_PORT=${QUARKUS_HTTP_SSL_PORT:-18420}
export QUARKUS_HTTP_HOST=127.0.0.1
export PAYMENTS_SEED_DEMO=true
export PAYMENT_RATE_PER_MINUTE=12000
printf '%s\n' "$$" > target/verification-app.pid
exec "$JAVA_HOME/bin/java" -jar target/quarkus-app/quarkus-run.jar
