#!/usr/bin/env python3
"""Packaged-service PostgreSQL persistence and response-loss smoke test; Docker + Java 21 required."""
import subprocess, pathlib, os, ssl, urllib.request, urllib.error, json, time, uuid, datetime
root=pathlib.Path(__file__).resolve().parents[1]
local=root/'.local';local.mkdir(exist_ok=True)
name='payments05-smoke-'+uuid.uuid4().hex[:8]
process=None
log=(local/'restart-smoke.log').open('w')
def run(*args,**kwargs):return subprocess.run(args,check=True,capture_output=True,**kwargs).stdout.decode().strip()
try:
    token=run('python3',str(root/'scripts/dev-identity.py'))
    cert=local/'localhost.pem';key=local/'localhost-key.pem'
    run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-keyout',str(key),'-out',str(cert))
    run('docker','run','-d','--name',name,'-e','POSTGRES_DB=payments','-e','POSTGRES_USER=payments','-e','POSTGRES_PASSWORD=local-only','-p','127.0.0.1::5432','postgres:16-alpine')
    port=run('docker','inspect','-f','{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}',name)
    for _ in range(60):
        ready=subprocess.run(['docker','exec',name,'pg_isready','-U','payments','-d','payments'],capture_output=True)
        if ready.returncode==0:break
        time.sleep(0.25)
    env=os.environ|{'DB_URL':f'jdbc:postgresql://localhost:{port}/payments','DB_USER':'payments','DB_PASSWORD':'local-only','JWT_ISSUER':'https://local.payment.invalid','JWT_PUBLIC_KEY_LOCATION':'file:'+str(local/'public.pem'),'QUARKUS_HTTP_SSL_CERTIFICATE_FILES':str(cert),'QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES':str(key),'QUARKUS_HTTP_SSL_PORT':'18445','QUARKUS_OTEL_SDK_DISABLED':'true','PAYMENT_RATE_LIMIT':'20000'}
    context=ssl.create_default_context(cafile=str(cert))
    def request(path,body=None):
        req=urllib.request.Request('https://localhost:18445'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,context=context,timeout=5) as response:return response.status,json.loads(response.read())
    def start():
        p=subprocess.Popen(['java','-jar',str(root/'target/quarkus-app/quarkus-run.jar')],cwd=root,env=env,stdout=log,stderr=log)
        for _ in range(120):
            if p.poll() is not None:raise RuntimeError('service exited; inspect .local/restart-smoke.log')
            try:
                req=urllib.request.Request('https://localhost:18445/q/health/ready')
                with urllib.request.urlopen(req,context=context,timeout=1) as r:
                    if r.status==200:return p
            except Exception:pass
            time.sleep(0.25)
        p.terminate();p.wait(timeout=20);raise RuntimeError('startup timeout')
    process=start()
    run('docker','exec','-i',name,'psql','-U','payments','-d','payments',input=(root/'ops/dev-seed.sql').read_bytes())
    payment={'paymentId':str(uuid.uuid4()),'fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'125.750','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'restart verification'}
    status,original=request('/api/v1/payments',payment);assert status==201,(status,original)
    # Discarding the caller's result then retrying after restart models lost response after commit.
    process.terminate();process.wait(timeout=20);process=start()
    status,stored=request('/api/v1/payments/'+payment['paymentId']);assert status==200 and stored==original
    status,replayed=request('/api/v1/payments',payment);assert status==200 and replayed==original
    _,balance=request('/api/v1/accounts/ACC-1001/balance');assert balance['availableBalance']=='374.250',balance
    _,reconciliation=request('/api/v1/admin/reconciliation');assert not reconciliation['issues'],reconciliation
    print('PASS: packaged TLS/JWT service restart, durable original receipt, response-loss replay, exact balance and reconciliation')
    duration=int(os.getenv('LOAD_SECONDS','0'))
    if duration:
        from concurrent.futures import ThreadPoolExecutor
        import platform, math
        run('docker','exec',name,'psql','-U','payments','-d','payments','-c',"INSERT INTO account_debit_grants(account_id,principal) VALUES ('ACC-2002','alice')")
        def one(i):
            payload=payment|{'paymentId':str(uuid.uuid4()),'amount':'0.001','fromAccount':'ACC-1001' if i%2==0 else 'ACC-2002','toAccount':'ACC-2002' if i%2==0 else 'ACC-1001','purpose':'load measurement'}
            begin=time.perf_counter()
            try:
                status,_=request('/api/v1/payments',payload)
                return (time.perf_counter()-begin)*1000,status==201
            except Exception:return (time.perf_counter()-begin)*1000,False
        start=time.perf_counter()
        with ThreadPoolExecutor(max_workers=30) as pool:
            futures=[]
            for i in range(duration*100):
                delay=start+i/100-time.perf_counter()
                if delay>0:time.sleep(delay)
                futures.append(pool.submit(one,i))
            samples=[f.result() for f in futures]
        elapsed=time.perf_counter()-start
        latencies=sorted(item[0] for item in samples)
        _,reconciliation=request('/api/v1/admin/reconciliation')
        assert not reconciliation['issues'],reconciliation
        result={'requests':len(samples),'target_rps':100,'elapsed_seconds':elapsed,'achieved_rps':len(samples)/elapsed,'p95_ms':latencies[math.ceil(len(latencies)*0.95)-1],'failures':sum(not x[1] for x in samples),'reconciliation_issues':len(reconciliation['issues']),'environment':'Shared development host; one TLS/JWT JVM and PostgreSQL 16 container; fresh TLS connection per request','platform':platform.platform(),'cpu_count':os.cpu_count()}
        (root/'docs/load-results.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result))
        assert result['failures']==0,result
        assert result['p95_ms']<500,result

finally:
    if process is not None and process.poll() is None:
        process.terminate();process.wait(timeout=20)
    subprocess.run(['docker','rm','-f',name],capture_output=True)
    log.close()
