#!/usr/bin/env python3
"""Open-loop reference load at 100 rps; all requests use independent IDs. Requires funded dev accounts."""
import concurrent.futures, datetime, json, os, statistics, time, urllib.request, urllib.error, uuid
URL=os.environ.get('PAYMENTS_URL','http://localhost:18015')
TOKEN=os.environ['TOKEN']
RATE=int(os.environ.get('RATE','100')); SECONDS=int(os.environ.get('SECONDS','30'))
def request(i):
    # Alternating directions require an explicit debit grant for both dev accounts.
    a,b=('ACC-1001','ACC-2002') if i%2==0 else ('ACC-2002','ACC-1001')
    body=json.dumps(dict(paymentId=str(uuid.uuid4()),fromAccount=a,toAccount=b,amount='0.001',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='reference load')).encode()
    req=urllib.request.Request(URL+'/api/v1/payments',data=body,headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
    start=time.perf_counter()
    try:
        with urllib.request.urlopen(req,timeout=15) as response: response.read(); status=response.status
    except urllib.error.HTTPError as error: status=error.code
    return (time.perf_counter()-start)*1000,status
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    start=time.perf_counter(); futures=[]
    for i in range(RATE*SECONDS):
        delay=start+i/RATE-time.perf_counter()
        if delay>0: time.sleep(delay)
        futures.append(pool.submit(request,i))
    results=[f.result() for f in futures]
latency=sorted(v for v,status in results);p95=latency[min(len(latency)-1,int(len(latency)*.95))]
report=dict(requests=len(results),target_rps=RATE,elapsed_seconds=time.perf_counter()-start,p95_ms=p95,status_counts={str(s):sum(code==s for _,code in results) for _,s in results},passed=p95<500 and all(s==201 for _,s in results))
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['passed'] else 1)
