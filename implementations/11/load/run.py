#!/usr/bin/env python3
"""HTTP reference load: RATE requests/sec for DURATION; TOKEN supplied only via environment."""
import concurrent.futures,datetime,json,os,time,urllib.request,uuid,platform
url=os.getenv('BASE_URL','http://localhost:8080')+'/api/v1/payments'
rate=int(os.getenv('RATE','100'));duration=int(os.getenv('DURATION','10'));token=os.environ['TOKEN']
def request(_):
    body=json.dumps(dict(paymentId=str(uuid.uuid4()),fromAccount='ACC-1001',toAccount='ACC-2002',amount='0.001',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='Reference load')).encode()
    req=urllib.request.Request(url,body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    start=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=10) as response:
            assert response.status==201
            response.read()
        return (time.monotonic()-start)*1000,None
    except Exception as exc:return (time.monotonic()-start)*1000,(('HTTP_'+str(exc.code)) if hasattr(exc,'code') else type(exc).__name__)
start=time.monotonic();futures=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    for i in range(rate*duration):
        delay=start+i/rate-time.monotonic()
        if delay>0:time.sleep(delay)
        futures.append(pool.submit(request,i))
    results=[f.result() for f in futures]
latencies=sorted(x[0] for x in results);errors=sum(x[1] is not None for x in results)
report=dict(requests=len(results),rate=rate,duration_seconds=round(time.monotonic()-start,3),errors=errors,p95_ms=round(latencies[int(len(latencies)*.95)-1],3),max_ms=round(max(latencies),3),host=platform.platform(),processors=os.cpu_count())
print(json.dumps(report,indent=2))
raise SystemExit(1 if errors or report['p95_ms']>=500 else 0)
