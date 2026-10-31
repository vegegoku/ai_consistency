#!/usr/bin/env python3
"""Issue a short-lived local-development token; never use the local issuer in production."""
import base64,json,pathlib,subprocess,time,sys
root=pathlib.Path(__file__).resolve().parent.parent
subject=sys.argv[1] if len(sys.argv)>1 else 'alice'
now=int(time.time())
def enc(value): return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
claims={'iss':'https://issuer.example.test','aud':'payments-api','sub':subject,'customer_id':subject,'iat':now,'exp':now+900,'groups':['admin'] if subject=='admin' else ['customer'],'scope':'payments:write payments:read accounts:read'+(' payments:admin' if subject=='admin' else '')}
payload=enc({'alg':'RS256','typ':'JWT'})+b'.'+enc(claims)
sig=subprocess.run(['openssl','dgst','-sha256','-sign',str(root/'deploy/jwt/private.pem')],input=payload,check=True,capture_output=True).stdout
print((payload+b'.'+base64.urlsafe_b64encode(sig).rstrip(b'=')).decode())
