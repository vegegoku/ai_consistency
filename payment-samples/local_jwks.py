#!/usr/bin/env python3
"""Serve one local RSA public key as a JWKS for development-only API runs."""

import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import jwt
from cryptography.hazmat.primitives.serialization import load_pem_public_key

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--public-key", type=Path, required=True)
p.add_argument("--port", type=int, default=18081)
p.add_argument("--kid", default="sample-runner")
p.add_argument("--issuer", default="")
a = p.parse_args()
key = load_pem_public_key(a.public_key.read_bytes())
jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key))
jwk.update({"kid": a.kid, "use": "sig", "alg": "RS256"})
data = json.dumps({"keys": [jwk]}).encode()

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if a.issuer and self.path.endswith("/.well-known/openid-configuration"):
            document = json.dumps({"issuer": a.issuer, "jwks_uri": f"http://127.0.0.1:{a.port}/jwks.json", "id_token_signing_alg_values_supported": ["RS256"]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(document)))
            self.end_headers()
            self.wfile.write(document)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *_):
        pass

print(f"Serving local sample JWKS on http://127.0.0.1:{a.port}/jwks.json", flush=True)
HTTPServer(("127.0.0.1", a.port), Handler).serve_forever()
