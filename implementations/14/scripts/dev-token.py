#!/usr/bin/env python3
"""Generate local-only RSA keys/JWKS and a short-lived JWT using OpenSSL, no Python packages."""
import base64, json, os, pathlib, subprocess, sys, time
root=pathlib.Path(__file__).resolve().parent.parent/'config'
root.mkdir(exist_ok=True,mode=0o700)
private=root/'private.pem'
if not private.exists():
    subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],check=True,stderr=subprocess.DEVNULL)
    private.chmod(0o600)
def b64(data): return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
modulus=subprocess.check_output(['openssl','rsa','-in',str(private),'-noout','-modulus'],stderr=subprocess.DEVNULL).decode().strip().split('=')[1]
public={'kty':'RSA','kid':'local-dev','use':'sig','alg':'RS256','n':b64(bytes.fromhex(modulus)),'e':'AQAB'}
(root/'public-jwks.json').write_text(json.dumps({'keys':[public]}))
header=b64(json.dumps({'alg':'RS256','kid':'local-dev','typ':'JWT'},separators=(',',':')).encode())
claims=b64(json.dumps({'iss':'https://local.example','aud':'payments','sub':sys.argv[1] if len(sys.argv)>1 else 'alice','scope':'payments:submit payments:read payments:admin','iat':int(time.time()),'exp':int(time.time())+3600},separators=(',',':')).encode())
message=(header+'.'+claims).encode()
signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(private)],input=message)
print(message.decode()+'.'+b64(signature))
