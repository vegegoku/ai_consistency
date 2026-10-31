#!/usr/bin/env python3
"""Issue a local development token. The bundled key is never a production credential."""
import base64,json,time,subprocess,pathlib,sys
enc=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=')
claims=dict(iss='local-payments',aud='payment-service',sub=sys.argv[1] if len(sys.argv)>1 else 'alice',iat=int(time.time()),exp=int(time.time())+900,scope=sys.argv[2] if len(sys.argv)>2 else 'payments:read payments:write')
body=enc(json.dumps(dict(alg='RS256',typ='JWT')).encode())+b'.'+enc(json.dumps(claims).encode())
key=pathlib.Path(__file__).with_name('local-private.pem')
signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(key)],input=body,capture_output=True,check=True).stdout
print((body+b'.'+enc(signature)).decode())
