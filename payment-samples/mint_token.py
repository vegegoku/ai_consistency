#!/usr/bin/env python3
"""Mint a short-lived RS256 token for a matching local JWKS validation run."""

import argparse
import time
from pathlib import Path

import jwt

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--private-key", type=Path, required=True)
p.add_argument("--issuer", required=True)
p.add_argument("--audience", required=True)
p.add_argument("--subject", default="alice")
p.add_argument("--scope", default="payments:write payments:read accounts:read payments:submit balances:read")
a = p.parse_args()
now = int(time.time())
print(jwt.encode({"sub": a.subject, "iss": a.issuer, "aud": a.audience, "iat": now, "exp": now + 3600, "scope": a.scope}, a.private_key.read_text(encoding="utf-8"), algorithm="RS256"))
