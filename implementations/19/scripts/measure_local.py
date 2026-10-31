#!/usr/bin/env python3
"""Isolated loopback HTTP/JWT/PostgreSQL load measurement. Requires Docker, Java 21 and cryptography.
Never points at an existing application or database. Writes docs/performance-result.json.
"""
import base64
import concurrent.futures
import datetime
import http.client
import http.server
import json
import os
import pathlib
import platform
import socket
import statistics
import subprocess
import threading
import time
import uuid
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes

ROOT = pathlib.Path(__file__).resolve().parents[1]
DURATION = int(os.environ.get('LOAD_SECONDS', '60'))
RATE = 100

def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()

def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
public = key.public_key().public_numbers()
def integer(v):
    return b64(v.to_bytes((v.bit_length()+7)//8, 'big'))
jwks = json.dumps({'keys':[{'kty':'RSA','kid':'load-key','use':'sig','alg':'RS256','n':integer(public.n),'e':integer(public.e)}]}).encode()
class Keys(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(jwks)))
        self.end_headers()
        self.wfile.write(jwks)
    def log_message(self, *args):
        pass
keys = http.server.ThreadingHTTPServer(('127.0.0.1',0),Keys)
threading.Thread(target=keys.serve_forever,daemon=True).start()
issuer = f'http://127.0.0.1:{keys.server_port}'
now = int(time.time())
head = b64(json.dumps({'alg':'RS256','kid':'load-key'}).encode())
claims = b64(json.dumps({'iss':issuer,'aud':['payment-service'],'sub':'alice','exp':now+600,'iat':now,'scope':'payments:write payments:read'}).encode())
payload = (head+'.'+claims).encode()
token = payload.decode()+'.'+b64(key.sign(payload,padding.PKCS1v15(),hashes.SHA256()))
container = None
process = None
log = None
app_port = port()
thread_local = threading.local()

def sql(statement):
    return run('docker','exec','-i',container,'psql','-U','postgres','-d','payments','-At','-c',statement)

def payment():
    start = time.perf_counter()
    try:
        conn = getattr(thread_local,'conn',None)
        if conn is None:
            conn = http.client.HTTPConnection('127.0.0.1',app_port,timeout=10)
            thread_local.conn = conn
        body = json.dumps({'paymentId':str(uuid.uuid4()),'fromAccount':'A','toAccount':'B','amount':'0.001','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Isolated load measurement'})
        conn.request('POST','/api/v1/payments',body,{'Content-Type':'application/json','Authorization':'Bearer '+token})
        response = conn.getresponse()
        response.read()
        return response.status,(time.perf_counter()-start)*1000
    except Exception:
        thread_local.conn = None
        return 0,(time.perf_counter()-start)*1000

try:
    container = run('docker','run','--rm','-d','-e','POSTGRES_PASSWORD=isolated-load-only','-e','POSTGRES_DB=payments','-p','127.0.0.1::5432','postgres:16-alpine')
    mapping = json.loads(run('docker','inspect',container))[0]['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
    for _ in range(100):
        check = subprocess.run(['docker','exec',container,'pg_isready','-U','postgres','-d','payments'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if check.returncode == 0: break
        time.sleep(.1)
    env = dict(os.environ, DB_URL=f'jdbc:postgresql://127.0.0.1:{mapping}/payments',DB_USER='postgres',DB_PASSWORD='isolated-load-only',REQUIRE_TLS='false',JWT_ISSUER=issuer,JWT_JWKS_URI=issuer+'/keys',TRACE_SAMPLING='0')
    log = open(ROOT/'performance-app.log','w')
    process = subprocess.Popen(['java','-Xmx512m','-jar',str(ROOT/'target/payment-service-1.0.0.jar'),f'--server.port={app_port}','--logging.level.root=WARN'],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(300):
        if process.poll() is not None: raise RuntimeError('Application exited; inspect performance-app.log')
        try:
            conn = http.client.HTTPConnection('127.0.0.1',app_port,timeout=1)
            conn.request('GET','/actuator/health/readiness')
            response = conn.getresponse(); response.read(); conn.close()
            if response.status == 200: break
        except OSError: pass
        time.sleep(.1)
    else: raise RuntimeError('Application readiness timed out')
    sql("INSERT INTO customers(id,display_name,status) VALUES ('alice','Load Alice','ACTIVE'),('bob','Load Bob','ACTIVE'); INSERT INTO accounts(id,customer_id,available_balance,opening_balance,status) VALUES ('A','alice',500,500,'ACTIVE'),('B','bob',100,100,'ACTIVE')")
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        warmup = [pool.submit(payment) for _ in range(100)]
        assert all(f.result()[0] == 201 for f in warmup), 'Warmup requests failed'
        time.sleep(1)
        started = time.perf_counter()
        requests = []
        for i in range(RATE*DURATION):
            delay = started+i/RATE-time.perf_counter()
            if delay>0: time.sleep(delay)
            requests.append(pool.submit(payment))
        values = [f.result() for f in requests]
        elapsed = time.perf_counter()-started
    latencies = sorted(v[1] for v in values)
    errors = sum(status != 201 for status,_ in values)
    accounting = sql("SELECT (SELECT count(*) FROM payments WHERE status='COMPLETED'),(SELECT count(*) FROM ledger_entries),(SELECT sum(available_balance) FROM accounts),(SELECT sum(signed_amount) FROM ledger_entries)")
    result = {'timestamp_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':{'os':platform.platform(),'cpu_count':os.cpu_count(),'java':run('java','-version',stderr=subprocess.STDOUT),'database':'PostgreSQL 16-alpine Docker','application':'Spring Boot 3.4.4, one replica, JVM Xmx512m, pool 20','transport':'Loopback HTTP, real RS256 JWT verification, persistent per-worker connections; local benchmark only'},'duration_seconds':DURATION,'submitted':len(values),'achieved_rps':len(values)/elapsed,'errors':errors,'p50_ms':statistics.median(latencies),'p95_ms':latencies[int(len(latencies)*.95)-1],'p99_ms':latencies[int(len(latencies)*.99)-1],'accounting_completed_ledger_totalbalance_ledgersum':accounting,'passes_initial_target':errors==0 and latencies[int(len(latencies)*.95)-1]<500}
    (ROOT/'docs/performance-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    assert errors == 0 and latencies[int(len(latencies)*.95)-1]<500,'Initial latency/success target not met'
    assert accounting == f'{100+len(values)}|{2*(100+len(values))}|600.000|0.000', 'Accounting invariant failed'
finally:
    if process:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill();process.wait()
    if container: subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    keys.shutdown()
    if log: log.close()
