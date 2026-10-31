#!/usr/bin/env python3
"""Create disposable local development keys and an RS256 JWT. Never use in production."""
import base64,json,os,pathlib,subprocess,time,sys
root=pathlib.Path(__file__).resolve().parents[1]
dev=root/'dev';dev.mkdir(exist_ok=True);os.chmod(dev,0o700)
private=dev/'privateKey.pem';public=dev/'publicKey.pem'
if not private.exists():
    subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private)],check=True,stderr=subprocess.DEVNULL)
    os.chmod(private,0o600)
    subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(public)],check=True)
def b64(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
now=int(time.time());subject=sys.argv[1] if len(sys.argv)>1 else 'alice'
claims={'iss':'https://local.payment.test','sub':subject,'aud':'payments-api','iat':now,'exp':now+3600,'scope':'payments:write payments:read accounts:read'}
if subject=='operator':claims['scope']='operations:read'
message=b'.'.join(b64(json.dumps(value,separators=(',',':')).encode()) for value in ({'alg':'RS256','typ':'JWT'},claims))
signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(private)],input=message,stdout=subprocess.PIPE,check=True).stdout
print((message+b'.'+b64(signature)).decode())
