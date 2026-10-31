#!/usr/bin/env sh
set -eu
# Local development only. Production uses the organization's OIDC flow.
curl --fail --silent --show-error --retry 20 --retry-all-errors --retry-delay 2 --max-time 10 http://localhost:18115/realms/payments/protocol/openid-connect/token \
  -d grant_type=password -d client_id=payments-local -d username=alice -d password=alice-local-only \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
