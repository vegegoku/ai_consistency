#!/usr/bin/env python3
"""Standard-library offered-rate load probe; requires dedicated funded test accounts."""
import concurrent.futures, datetime, json, math, os, time, urllib.request, uuid
base=os.environ.get('BASE_URL','http://localhost:8080')
token=os.environ['TOKEN']
source=os.environ.get('FROM_ACCOUNT','ACC-1001')
destination=os.environ.get('TO_ACCOUNT','ACC-2002')
rps=int(os.environ.get('LOAD_RPS','100'))
duration=int(os.environ.get('LOAD_SECONDS','30'))
def submit(_):
    body=json.dumps(dict(paymentId=str(uuid.uuid4()),fromAccount=source,toAccount=destination,amount='0.001',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='Dedicated load probe')).encode()
    req=urllib.request.Request(base+'/api/v1/payments',body,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    started=time.perf_counter()
    try:
        with urllib.request.urlopen(req,timeout=10) as response:
            response.read()
            return (time.perf_counter()-started)*1000,response.status==201
    except Exception:
        return (time.perf_counter()-started)*1000,False
started=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    futures=[]
    for i in range(rps*duration):
        target=started+i/rps
        if target>time.perf_counter(): time.sleep(target-time.perf_counter())
        futures.append(pool.submit(submit,i))
    results=[f.result() for f in futures]
latencies=sorted(x[0] for x in results)
p95=latencies[math.ceil(len(latencies)*.95)-1]
errors=sum(not x[1] for x in results)
print(json.dumps(dict(requests=len(results),errors=errors,offered_rps=rps,p95_ms=round(p95,3),elapsed_seconds=round(time.perf_counter()-started,3))))
raise SystemExit(1 if errors or p95>=500 else 0)
