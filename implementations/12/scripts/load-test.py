#!/usr/bin/env python3
"""Paced reference load; no third party packages. Provision funded source first."""
import argparse,concurrent.futures,datetime,json,os,statistics,time,urllib.request,urllib.error,uuid
p=argparse.ArgumentParser();p.add_argument('--url',default='http://localhost:8080');p.add_argument('--rate',type=int,default=100);p.add_argument('--seconds',type=int,default=60);p.add_argument('--from-account',default='ACC-1001');p.add_argument('--to-account',default='ACC-2002');p.add_argument('--output',default='load-results.json');a=p.parse_args()
token=os.environ['PAYMENT_TOKEN'];results=[];start=time.monotonic()
def send(_):
    body=json.dumps({'paymentId':str(uuid.uuid4()),'fromAccount':a.from_account,'toAccount':a.to_account,'amount':'0.001','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Reference load'}).encode()
    req=urllib.request.Request(a.url+'/api/v1/payments',data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    before=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=15) as response:status=response.status;response.read()
    except urllib.error.HTTPError as error:status=error.code
    except OSError:status=0
    return status,(time.monotonic()-before)*1000
with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
    futures=[]
    for i in range(a.rate*a.seconds):
        delay=start+i/a.rate-time.monotonic()
        if delay>0:time.sleep(delay)
        futures.append(pool.submit(send,i))
    results=[f.result() for f in futures]
latencies=sorted(x[1] for x in results);success=sum(x[0]==201 for x in results);p95=latencies[int(len(latencies)*.95)-1]
report={'requests':len(results),'success':success,'elapsedSeconds':time.monotonic()-start,'targetRps':a.rate,'p95Ms':p95,'statuses':{str(s):sum(x[0]==s for x in results) for s in set(x[0] for x in results)},'targetMet':success==len(results) and p95<500}
open(a.output,'w').write(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
raise SystemExit(0 if report['targetMet'] else 1)
