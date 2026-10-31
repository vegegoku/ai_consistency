#!/usr/bin/env python3
"""Issue a short-lived development JWT from a LOCAL private key. Never ship test keys.
Requires openssl. Example: python3 scripts/dev-token.py /tmp/dev-private.pem alice
"""
import base64, json, subprocess, sys, time
encode=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=')
subject=sys.argv[2] if len(sys.argv)>2 else 'alice'
header=encode(json.dumps({'alg':'RS256','typ':'JWT'},separators=(',',':')).encode())
claims={'sub':subject,'iss':'https://development.local','aud':'payments-api','iat':int(time.time()),'exp':int(time.time())+3600,'scope':'payments:write payments:read'}
payload=encode(json.dumps(claims,separators=(',',':')).encode())
unsigned=header+b'.'+payload
signature=subprocess.run(['openssl','dgst','-sha256','-sign',sys.argv[1]],input=unsigned,capture_output=True,check=True).stdout
print((unsigned+b'.'+encode(signature)).decode())
