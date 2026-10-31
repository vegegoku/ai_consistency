#!/usr/bin/env python3
"""Create an ephemeral local RS256 key and print a short-lived development token."""
import base64, json, pathlib, subprocess, time, os
root=pathlib.Path(__file__).resolve().parents[1]/'.local'
root.mkdir(mode=0o700,exist_ok=True)
private=root/'private.pem'; public=root/'public.pem'
if not private.exists():
    subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    private.chmod(0o600)
    subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True,stdout=subprocess.DEVNULL)
def b64(value): return base64.urlsafe_b64encode(value).rstrip(b'=')
now=int(time.time())
claims={'iss':'https://local.payment.invalid','aud':'payment-service','sub':os.getenv('DEV_SUBJECT','alice'),'iat':now,'exp':now+900,'scope':os.getenv('DEV_SCOPES','payments:read payments:write payments:admin')}
message=b64(json.dumps({'alg':'RS256','typ':'JWT'}).encode())+b'.'+b64(json.dumps(claims).encode())
sig=subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],input=message,check=True,capture_output=True).stdout
print((message+b'.'+b64(sig)).decode())
