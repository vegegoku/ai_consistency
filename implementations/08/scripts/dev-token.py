#!/usr/bin/env python3
"""Generate a short-lived local RS256 token using an untracked private key and OpenSSL."""
import base64, json, pathlib, subprocess, sys, time
root = pathlib.Path(__file__).resolve().parent.parent
keys = root / '.local'
keys.mkdir(exist_ok=True)
private = keys / 'private.pem'
public = keys / 'public.pem'
if not private.exists():
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(private)], check=True, stderr=subprocess.DEVNULL)
    private.chmod(0o600)
    subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout', '-out', str(public)], check=True)
def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=')
now = int(time.time())
claims = {'iss': 'https://local.payment.test', 'aud': 'payment-service', 'sub': sys.argv[1] if len(sys.argv)>1 else 'alice', 'iat': now, 'exp': now+600, 'scope': ' '.join(sys.argv[2:]) if len(sys.argv)>2 else 'payments:write payments:read balances:read'}
unsigned = b64(b'{"alg":"RS256","typ":"JWT"}')+b'.'+b64(json.dumps(claims, separators=(',', ':')).encode())
signature = subprocess.run(['openssl','dgst','-sha256','-sign',str(private)], input=unsigned, stdout=subprocess.PIPE, check=True).stdout
print((unsigned+b'.'+b64(signature)).decode())
