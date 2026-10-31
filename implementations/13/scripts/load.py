#!/usr/bin/env python3
"""100 requests/second open-loop test. Use only an isolated seeded development DB."""
import concurrent.futures,datetime,json,os,statistics,time,urllib.request,uuid
url=os.environ.get('PAYMENT_URL','http://localhost:8080')+'/api/v1/payments'
token=os.environ['PAYMENT_TOKEN'];seconds=int(os.environ.get('LOAD_SECONDS','30'));rate=100
# 0.001 JOD permits 30,000 successful operations from the 500.000 local seed.
def send(i):
    body=json.dumps(dict(paymentId=str(uuid.uuid4()),fromAccount='ACC-1001',toAccount='ACC-2002',amount='0.001',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='Load test')).encode()
    start=time.monotonic()
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}),timeout=15) as response:
            response.read();return (time.monotonic()-start)*1000,response.status
    except Exception as e:return (time.monotonic()-start)*1000,getattr(e,'code',0)
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    begin=time.monotonic();futures=[]
    for i in range(seconds*rate):
        target=begin+i/rate;time.sleep(max(0,target-time.monotonic()));futures.append(pool.submit(send,i))
    results=[f.result() for f in futures]
latencies=sorted(x[0] for x in results);errors=sum(x[1]!=201 for x in results);p95=latencies[int(.95*(len(latencies)-1))]
print(json.dumps(dict(requests=len(results),durationSeconds=time.monotonic()-begin,p95Milliseconds=round(p95,3),errors=errors,success=errors==0 and p95<500),indent=2))
raise SystemExit(errors>0 or p95>=500)
