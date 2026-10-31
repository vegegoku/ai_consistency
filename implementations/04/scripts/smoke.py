#!/usr/bin/env python3
"""Packaged application HTTPS/restart/load check, using its own disposable database.
Run after mvn verify. Requires Docker, openssl, Java 21 and generated files under target/smoke.
The implementation report records the actual invocation and results.
"""
import datetime, json, os, pathlib, signal, ssl, subprocess, sys, time, urllib.request, uuid
root=pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
state=root/'target/smoke'
port=(state/'db-port.txt').read_text().strip().rsplit(':',1)[1]
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin/java'
env=os.environ.copy()
env.update(DB_URL=f'jdbc:postgresql://localhost:{port}/payments?connectTimeout=3&socketTimeout=10',DB_USER='payments',DB_PASSWORD='local-smoke-only',JWT_ISSUER='https://development.local',JWT_AUDIENCE='payments-api',JWT_PUBLIC_KEY=str(state/'public.pem'),QUARKUS_HTTP_SSL_CERTIFICATE_FILES=str(state/'tls.pem'),QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=str(state/'tls.key'),QUARKUS_HTTP_SSL_PORT='18444',PAYMENTS_RATE_PER_MINUTE='20000')
context=ssl.create_default_context(cafile=str(state/'tls.pem'))
token=subprocess.check_output([sys.executable,'scripts/dev-token.py',str(state/'private.pem'),'alice'],text=True).strip()
base='https://localhost:18444'
process=None

def http(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(base+path,data,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with urllib.request.urlopen(req,context=context,timeout=10) as r:
        return r.status,json.loads(r.read())

def start(name):
    log=open(state/name,'w')
    p=subprocess.Popen([str(java),'-jar','target/quarkus-app/quarkus-run.jar'],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(100):
        if p.poll() is not None:raise RuntimeError('Application startup failed; inspect '+name)
        try:
            if http('/q/health/ready')[0]==200:return p
        except Exception:time.sleep(.1)
    p.terminate()
    raise RuntimeError('Readiness timeout')

def stop(p):
    p.terminate()
    p.wait(timeout=15)

try:
    for _ in range(100):
        ready=subprocess.run(['docker','exec','payment04-smoke','pg_isready','-U','payments','-d','payments'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if ready.returncode==0:break
        time.sleep(.1)
    else:raise RuntimeError('PostgreSQL readiness timeout')
    process=start('first-process.log')
    subprocess.run(['docker','exec','-i','payment04-smoke','psql','-U','payments','-d','payments'],input=(root/'ops/seed.sql').read_bytes(),check=True,stdout=subprocess.DEVNULL)
    payment=dict(paymentId=str(uuid.uuid4()),fromAccount='ACC-1001',toAccount='ACC-2002',amount='125.750',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='Packaged restart proof')
    status,original=http('/api/v1/payments',payment)
    assert status==201, (status,original)
    stop(process)
    process=None
    process=start('restarted-process.log')
    assert http('/api/v1/payments/'+payment['paymentId'])[1]==original
    status,replay=http('/api/v1/payments',payment)
    assert status==200 and replay==original
    assert http('/api/v1/accounts/ACC-1001/balance')[1]['availableBalance']=='374.250'
    print('Packaged HTTPS process restart, receipt durability, replay and balance: PASS',flush=True)
    loadenv=env.copy()
    loadenv['PAYMENT_TOKEN']=token
    loadenv['SSL_CERT_FILE']=str(state/'tls.pem')
    result=subprocess.run([sys.executable,'scripts/load.py','--url',base,'--seconds','60','--rate','100'],env=loadenv,text=True,capture_output=True)
    (state/'load-results.json').write_text(result.stdout)
    print(result.stdout,flush=True)
    if result.returncode!=0:raise RuntimeError('Load target not met: '+result.stderr)
    counts=subprocess.check_output(['docker','exec','payment04-smoke','psql','-At','-U','payments','-d','payments','-c',"SELECT count(*),sum(amount) FROM payments WHERE status='COMPLETED'; SELECT count(*),sum(signed_amount) FROM ledger_entries; SELECT id,available_balance FROM accounts ORDER BY id;"],text=True)
    (state/'database-proof.txt').write_text(counts)
    print(counts)
finally:
    if process is not None:stop(process)
    subprocess.run(['docker','stop','payment04-smoke'],stdout=subprocess.DEVNULL,check=False)
    for key in ('private.pem','tls.key'):
        (state/key).unlink(missing_ok=True)
