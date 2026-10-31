#!/usr/bin/env python3
"""Optional smoke against the local Compose DB on POSTGRES_PORT (default 5432)."""
import datetime, json, os, pathlib, subprocess, time, urllib.request, uuid
root=pathlib.Path(__file__).resolve().parent.parent
token=subprocess.check_output(['python3',str(root/'scripts/dev-token.py')],text=True).strip()
env=dict(os.environ, DATABASE_URL='jdbc:postgresql://localhost:'+os.environ.get('POSTGRES_PORT','5432')+'/payments', DATABASE_USER='payments_app', DATABASE_PASSWORD=os.environ.get('DATABASE_PASSWORD','local-app-only'), QUARKUS_FLYWAY_USERNAME='payments_owner', QUARKUS_FLYWAY_PASSWORD=os.environ.get('POSTGRES_OWNER_PASSWORD','local-owner-only'), JWT_ISSUER='https://local.payment.test', JWT_PUBLIC_KEY_LOCATION=str(root/'.local/public.pem'), QUARKUS_HTTP_INSECURE_REQUESTS='enabled', QUARKUS_HTTP_PORT='18008', QUARKUS_OTEL_SDK_DISABLED='true')
java=pathlib.Path(os.environ.get('JAVA_HOME','/usr'))/'bin/java'
with (root/'target/local-smoke.log').open('w') as output:
 process=subprocess.Popen([str(java),'-jar',str(root/'target/quarkus-app/quarkus-run.jar')],cwd=root,env=env,stdout=output,stderr=subprocess.STDOUT)
 try:
  for attempt in range(300):
   if process.poll() is not None:raise RuntimeError('Application exited; inspect target/local-smoke.log')
   try:
    with urllib.request.urlopen('http://localhost:18008/q/health/ready',timeout=1) as response:
     if response.status==200:break
   except Exception:time.sleep(.1)
  else:raise RuntimeError('Readiness deadline exceeded')
  subprocess.run(['docker','compose','-f',str(root/'compose.yaml'),'exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','payments_owner','-d','payments'],input=(root/'src/main/resources/db/dev/R__development_accounts.sql').read_bytes(),check=True,stdout=subprocess.DEVNULL)
  body={'paymentId':str(uuid.uuid4()),'fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'0.001','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Least privilege smoke'}
  request=urllib.request.Request('http://localhost:18008/api/v1/payments',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
  with urllib.request.urlopen(request) as response:
   assert response.status==201
   receipt=json.load(response)
   assert receipt['amount']=='0.001' and receipt['status']=='COMPLETED'
  print('Compose migration-owner/runtime-app separation and seeded-account transfer: PASS')
 finally:
  process.terminate()
  try:process.wait(timeout=15)
  except subprocess.TimeoutExpired:process.kill();process.wait()
