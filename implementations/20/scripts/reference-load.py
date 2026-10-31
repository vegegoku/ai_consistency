#!/usr/bin/env python3
"""Measured 100 RPS HTTPS reference workload. Set TOKEN and BASE_URL; local development only."""
import concurrent.futures,datetime,json,os,pathlib,ssl,statistics,time,urllib.request,urllib.error,uuid
base=os.environ.get('BASE_URL','https://localhost:8443')
token=os.environ['TOKEN']
rate=int(os.environ.get('LOAD_RPS','100'))
seconds=int(os.environ.get('LOAD_SECONDS','10'))
context=ssl.create_default_context(cafile=str(pathlib.Path(__file__).resolve().parent.parent/'deploy/tls/cert.pem'))
def transfer(index):
    body={'paymentId':str(uuid.uuid4()),'fromAccount':'ACC-1001','toAccount':'ACC-2002','amount':'0.001','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Reference performance test'}
    req=urllib.request.Request(base+'/api/v1/payments',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    start=time.monotonic()
    try:
        with urllib.request.urlopen(req,context=context,timeout=15) as result:
            result.read();status=result.status
    except urllib.error.HTTPError as error:
        error.read();status=error.code
    return {'status':status,'milliseconds':(time.monotonic()-start)*1000}
start=time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    futures=[]
    for index in range(rate*seconds):
        delay=start+index/rate-time.monotonic()
        if delay>0:time.sleep(delay)
        futures.append(pool.submit(transfer,index))
    results=[f.result() for f in futures]
elapsed=time.monotonic()-start
latency=sorted(x['milliseconds'] for x in results)
report={'requests':len(results),'durationSeconds':round(elapsed,3),'achievedRps':round(len(results)/elapsed,2),'completed':sum(x['status']==201 for x in results),'p50Milliseconds':round(statistics.median(latency),3),'p95Milliseconds':round(latency[int(len(latency)*.95)-1],3),'maximumMilliseconds':round(max(latency),3),'statusCounts':{str(code):sum(x['status']==code for x in results) for code in set(x['status'] for x in results)}}
print(json.dumps(report,indent=2))
if report['completed']!=report['requests'] or report['p95Milliseconds']>=500:raise SystemExit(1)
