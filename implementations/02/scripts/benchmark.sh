#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
container=""
app_pid=""
cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
container=$(docker run --rm -d -e POSTGRES_PASSWORD=local-benchmark -e POSTGRES_USER=payments -e POSTGRES_DB=payments -p 127.0.0.1::5432 postgres:16-alpine)
db_port=$(docker port "$container" 5432/tcp | awk -F: '{print $NF}')
for i in {1..100}; do if docker exec "$container" pg_isready -U payments -d payments >/dev/null 2>&1; then break; fi; sleep .1; done
app_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
DB_URL="jdbc:postgresql://localhost:$db_port/payments" DB_USER=payments DB_PASSWORD=local-benchmark \
JWT_ISSUER=http://localhost:8080/local-issuer JWT_PUBLIC_KEY_LOCATION="$PWD/dev/public-key.pem" \
QUARKUS_HTTP_PORT="$app_port" QUARKUS_HTTP_INSECURE_REQUESTS=enabled PAYMENTS_REQUIRE_TLS=false \
PAYMENTS_RATE_PER_MINUTE=100000 QUARKUS_OTEL_SDK_DISABLED=true \
"$JAVA_HOME/bin/java" -Xmx256m -XX:ActiveProcessorCount=4 -jar target/quarkus-app/quarkus-run.jar > target/benchmark-app.log 2>&1 &
app_pid=$!
for i in {1..100}; do if curl -fsS "http://localhost:$app_port/q/health/ready" >/dev/null 2>&1; then break; fi; sleep .1; done
docker exec -i "$container" psql -U payments -d payments < dev/seed.sql >/dev/null
TOKEN=$(python3 scripts/mint_dev_token.py alice) BASE_URL="http://localhost:$app_port" python3 scripts/load_test.py
# Verify both the ledger and the opening-balance reconciliation after the run.
TOKEN=$(python3 scripts/mint_dev_token.py alice)
curl -fsS "http://localhost:$app_port/api/v1/admin/reconciliation" -H "Authorization: Bearer $TOKEN" > target/load-reconciliation.json
python3 -c 'import json; r=json.load(open("target/load-reconciliation.json")); assert r["healthy"],r; print("Post-load reconciliation: healthy")'
docker exec "$container" psql -U payments -d payments -Atc "SELECT count(*),sum(signed_amount) FROM ledger_entries" > target/load-ledger.txt
