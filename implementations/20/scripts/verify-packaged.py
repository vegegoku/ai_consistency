#!/usr/bin/env python3
"""Isolated packaged HTTPS, 100-RPS, restart, database-outage and recovery acceptance check."""
import datetime,json,os,pathlib,socket,ssl,subprocess,time,urllib.error,urllib.request,uuid
ROOT=pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)
JAVA=pathlib.Path(os.environ['JAVA_HOME'])/'bin/java'
name='payment20-check-'+uuid.uuid4().hex[:8]
server=None
log=None
report={}
def docker(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def wait_ready():
    for _ in range(100):
        try:
            if request('/q/health/ready')[0]==200:return
        except Exception:time.sleep(.1)
    raise AssertionError('Packaged service failed readiness; inspect target/packaged-verification.log')
def start():
    global server,log
    log=open(ROOT/'target/packaged-verification.log','a')
    server=subprocess.Popen([str(JAVA),'-jar','target/quarkus-app/quarkus-run.jar'],env=environment,stdout=log,stderr=subprocess.STDOUT)
    wait_ready()
def stop():
    global server,log
    if server is not None:
        server.terminate()
        try:server.wait(timeout=15)
        except subprocess.TimeoutExpired:server.kill();server.wait()
        server=None
    if log:log.close();log=None
def request(path,body=None,identity=None):
    data=None if body is None else json.dumps(body).encode()
    headers={'Content-Type':'application/json'}
    if identity:headers['Authorization']='Bearer '+identity
    with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),context=context,timeout=15) as response:return response.status,json.load(response)
try:
    subprocess.run(['bash','scripts/dev-setup.sh'],check=True,capture_output=True)
    with socket.socket() as candidate:candidate.bind(('127.0.0.1',0));db_port=str(candidate.getsockname()[1])
    docker('run','-d','--name',name,'-p','127.0.0.1:'+db_port+':5432','-e','POSTGRES_DB=payments','-e','POSTGRES_USER=payments','-e','POSTGRES_PASSWORD=local-development-only','postgres:14')
    db_port=docker('port',name,'5432/tcp').split(':')[-1]
    with socket.socket() as candidate:candidate.bind(('127.0.0.1',0));port=candidate.getsockname()[1]
    base='https://localhost:'+str(port)
    context=ssl.create_default_context(cafile=str(ROOT/'deploy/tls/cert.pem'))
    environment={**os.environ,'DB_URL':'jdbc:postgresql://localhost:'+db_port+'/payments','DB_USER':'payments','DB_PASSWORD':'local-development-only','JWT_PUBLIC_KEY':'file:'+str(ROOT/'deploy/jwt/public.pem'),'QUARKUS_HTTP_SSL_CERTIFICATE_FILES':str(ROOT/'deploy/tls/cert.pem'),'QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES':str(ROOT/'deploy/tls/key.pem'),'QUARKUS_HTTP_SSL_PORT':str(port),'QUARKUS_HTTP_HOST':'127.0.0.1','PAYMENTS_SEED_DEMO':'true','PAYMENT_RATE_PER_MINUTE':'12000'}
    for _ in range(100):
        if subprocess.run(['docker','exec',name,'pg_isready','-U','payments','-d','payments'],capture_output=True).returncode==0:break
        time.sleep(.1)
    start()
    token=subprocess.check_output(['python3','scripts/dev-token.py','alice'],text=True).strip()
    admin=subprocess.check_output(['python3','scripts/dev-token.py','admin'],text=True).strip()
    body={'paymentId':str(uuid.uuid4()),'fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'125.750','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Packaged restart verification'}
    status,receipt=request('/api/v1/payments',body,token);assert status==201 and receipt['amount']=='125.750'
    load=subprocess.run(['python3','scripts/reference-load.py'],env={**os.environ,'TOKEN':token,'BASE_URL':base},check=True,capture_output=True,text=True)
    report['load']=json.loads(load.stdout)
    stop();start()
    status,replayed=request('/api/v1/payments',body,token);assert status==200 and replayed==receipt
    assert request('/api/v1/payments/'+body['paymentId'],identity=token)==(200,receipt)
    assert request('/api/v1/accounts/ACC-1001/balance',identity=token)[1]['availableBalance']=='373.250'
    assert request('/api/v1/admin/reconciliation',identity=admin)[1]=={'issues':[]}
    report['applicationRestart']='PASS';report['sameIdReplay']='PASS'
    docker('stop','-t','2',name)
    try:
        try:request('/q/health/ready');raise AssertionError('Readiness unexpectedly succeeded during outage')
        except urllib.error.HTTPError as error:assert error.code==503
        try:request('/api/v1/payments',body,token);raise AssertionError('Transfer unexpectedly succeeded during outage')
        except urllib.error.HTTPError as error:assert error.code==503 and json.load(error)['code']=='TEMPORARILY_UNAVAILABLE'
    finally:docker('start',name)
    for _ in range(100):
        try:
            assert request('/api/v1/payments',body,token)==(200,receipt);break
        except Exception:time.sleep(.1)
    else:raise AssertionError('Database recovery failed')
    assert request('/api/v1/accounts/ACC-1001/balance',identity=token)[1]['availableBalance']=='373.250'
    assert request('/api/v1/admin/reconciliation',identity=admin)[1]=={'issues':[]}
    report.update(databaseOutage='PASS',databaseRecovery='PASS',reconciliation='PASS',sourceBalance='373.250')
    pathlib.Path('target/packaged-verification.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
finally:
    stop()
    subprocess.run(['docker','stop','-t','2',name],capture_output=True)
    subprocess.run(['docker','container','rm',name],capture_output=True)
