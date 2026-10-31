#!/usr/bin/env python3
"""Mint a short-lived development token with the test-only private key, using openssl."""
import base64, json, subprocess, time, pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=')
now = int(time.time())
claims = {'iss':'http://localhost:8080/local-issuer','sub':sys.argv[1] if len(sys.argv)>1 else 'alice',
          'aud':'payments-api','iat':now,'exp':now+600,'scope':'payments:submit payments:read payments:admin'}
message = b'.'.join(b64(json.dumps(o,separators=(',',':')).encode()) for o in ({'alg':'RS256','typ':'JWT'},claims))
sig = subprocess.run(['openssl','dgst','-sha256','-sign',str(root/'src/test/resources/private-key.pem')],input=message,check=True,stdout=subprocess.PIPE).stdout
print((message+b'.'+b64(sig)).decode())
