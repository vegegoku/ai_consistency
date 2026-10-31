#!/usr/bin/env python3
"""Run a reproducible local PostgreSQL + packaged application reference benchmark."""
import json,os,pathlib,socket,subprocess,time,urllib.request,uuid
root=pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
name='payment-reference-'+str(uuid.uuid4())[:8]
process=None
log=None
try:
    subprocess.run(['docker','run','-d','--rm','--name',name,'-e','POSTGRES_DB=payments','-e','POSTGRES_USER=payments','-e','POSTGRES_PASSWORD=local-reference-only','-p','127.0.0.1::5432','postgres:16-alpine'],check=True,stdout=subprocess.DEVNULL)
    for _ in range(150):
        if subprocess.run(['docker','exec',name,'pg_isready','-U','payments','-d','payments'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
        time.sleep(.1)
    else:raise RuntimeError('PostgreSQL readiness deadline')
    db_port=int(subprocess.check_output(['docker','port',name,'5432'],text=True).strip().rsplit(':',1)[1])
    with socket.socket() as s:s.bind(('127.0.0.1',0));http_port=s.getsockname()[1]
    token=subprocess.check_output(['python3','scripts/create-dev-token.py','alice'],text=True).strip()
    env=os.environ.copy()
    env.update({'DB_URL':f'jdbc:postgresql://127.0.0.1:{db_port}/payments','DB_USER':'payments','DB_PASSWORD':'local-reference-only','JWT_PUBLIC_KEY':(root/'dev/publicKey.pem').as_uri(),'JWT_ISSUER':'https://local.payment.test','PAYMENT_RATE_PER_MINUTE':'10000','PAYMENT_REQUIRE_TLS':'false','QUARKUS_HTTP_INSECURE_REQUESTS':'enabled','QUARKUS_HTTP_PORT':str(http_port),'QUARKUS_OTEL_SDK_DISABLED':'true','PAYMENT_TOKEN':token})
    java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    log=open('target/reference-app.log','w')
    process=subprocess.Popen([java,'-jar','target/quarkus-app/quarkus-run.jar'],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(300):
        if process.poll() is not None:raise RuntimeError('Application exited; inspect target/reference-app.log')
        try:
            with urllib.request.urlopen(f'http://localhost:{http_port}/q/health/ready',timeout=1) as response:
                if response.status==200:break
        except OSError:time.sleep(.1)
    else:raise RuntimeError('Application readiness deadline')
    subprocess.run(['docker','exec','-i',name,'psql','-U','payments','-d','payments'],input=pathlib.Path('scripts/dev-seed.sql').read_bytes(),check=True,stdout=subprocess.DEVNULL)
    result=subprocess.run(['python3','scripts/load-test.py','--url',f'http://localhost:{http_port}','--rate','100','--seconds','60','--output','docs/load-results.json'],env=env)
    audit=subprocess.check_output(['docker','exec',name,'psql','-U','payments','-d','payments','-Atc',"SELECT count(*),sum(amount) FROM payments WHERE status='COMPLETED'; SELECT count(*),sum(signed_amount) FROM ledger_entries; SELECT id,available_balance FROM accounts ORDER BY id; SELECT sum(available_balance) FROM accounts;"],text=True)
    pathlib.Path('docs/load-reconciliation.txt').write_text(audit)
    print('Accounting verification:\n'+audit,flush=True)
    raise SystemExit(result.returncode)
finally:
    if process is not None:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    if log is not None:log.close()
    subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
