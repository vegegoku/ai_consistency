#!/usr/bin/env python3
"""Dependency-free reference load driver: 100 arrivals/second for 60 seconds."""
import concurrent.futures, datetime, http.client, json, os, pathlib, platform, statistics, threading, time, uuid
rate=int(os.getenv('LOAD_RATE','100')); duration=int(os.getenv('LOAD_SECONDS','60'))
root=pathlib.Path(__file__).resolve().parent.parent
token=os.getenv('TOKEN') or (root/'config/token.txt').read_text().strip()
host=os.getenv('LOAD_HOST','localhost:18014')
local=threading.local()
def send(i):
    if not hasattr(local,'connection'):local.connection=http.client.HTTPConnection(host,timeout=10)
    body=json.dumps({'paymentId':str(uuid.uuid4()),'fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'0.001','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'load-test'})
    start=time.perf_counter()
    try:
        local.connection.request('POST','/api/v1/payments',body,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        response=local.connection.getresponse();response.read();return response.status,(time.perf_counter()-start)*1000
    except Exception:return 0,(time.perf_counter()-start)*1000
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=40) as pool:
    tasks=[]
    for i in range(rate*duration):
        delay=start+i/rate-time.perf_counter()
        if delay>0:time.sleep(delay)
        tasks.append(pool.submit(send,i))
    values=[f.result() for f in tasks]
latencies=sorted(ms for _,ms in values);statuses={str(status):sum(1 for s,_ in values if s==status) for status in sorted(set(s for s,_ in values))}
report={'date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':platform.platform(),'database':'PostgreSQL 16.15 local Docker','service':'Java 21 JDK HTTP, local HTTP loopback, same source account','ratePerSecond':rate,'durationSeconds':duration,'requests':len(values),'elapsedSeconds':round(time.perf_counter()-start,3),'statuses':statuses,'p50Ms':round(statistics.median(latencies),3),'p95Ms':round(latencies[int(len(latencies)*0.95)-1],3),'maxMs':round(max(latencies),3)}
report['passed']=report['p95Ms']<500 and statuses.get('201',0)==len(values)
(root/'docs/performance.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
raise SystemExit(0 if report['passed'] else 1)
