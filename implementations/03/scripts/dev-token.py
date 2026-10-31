#!/usr/bin/env python3
"""Generate a development-only RSA JWT using openssl; never deploy these keys."""
import base64,json,time,subprocess,pathlib
root=pathlib.Path(__file__).resolve().parent.parent
private=root/'dev/privateKey.pem'; public=root/'dev/publicKey.pem'
if not private.exists():
 subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],check=True,stderr=subprocess.DEVNULL)
 private.chmod(0o600)
 subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True)
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=')
now=int(time.time()); header=b64(json.dumps({'alg':'RS256','typ':'JWT'}).encode());payload=b64(json.dumps({'sub':'alice','iss':'https://issuer.example.test','aud':'payments','iat':now,'exp':now+3600,'scope':'payments:write payments:read payments:admin'}).encode())
data=header+b'.'+payload
signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],input=data,stdout=subprocess.PIPE,check=True).stdout
print((data+b'.'+b64(signature)).decode())
