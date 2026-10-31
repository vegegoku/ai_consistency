#!/usr/bin/env python3
"""Constant-arrival HTTP benchmark. Requires dedicated fixtures and PAYMENT_TOKEN.
Example: PAYMENT_TOKEN=... python3 scripts/load.py --url https://localhost:8443 --seconds 60
No credentials or token content are printed. TLS verification is always enabled.
"""
import argparse, concurrent.futures, datetime, json, os, time, uuid, urllib.request, urllib.error, statistics
p=argparse.ArgumentParser()
p.add_argument('--url',default='https://localhost:8443')
p.add_argument('--seconds',type=int,default=60)
p.add_argument('--rate',type=int,default=100)
p.add_argument('--from-account',default='ACC-1001')
p.add_argument('--to-account',default='ACC-2002')
a=p.parse_args()
token=os.environ['PAYMENT_TOKEN']
def send():
    body=json.dumps(dict(paymentId=str(uuid.uuid4()),fromAccount=a.from_account,toAccount=a.to_account,amount='0.001',date=datetime.datetime.now(datetime.timezone.utc).isoformat(),purpose='Benchmark')).encode()
    request=urllib.request.Request(a.url+'/api/v1/payments',body,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    started=time.monotonic()
    try:
        with urllib.request.urlopen(request,timeout=10) as r:
            r.read()
            return r.status,(time.monotonic()-started)*1000
    except urllib.error.HTTPError as e:
        return e.code,(time.monotonic()-started)*1000
    except Exception:
        return 0,(time.monotonic()-started)*1000
start=time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as ex:
    tasks=[]
    for n in range(a.seconds*a.rate):
        delay=start+n/a.rate-time.monotonic()
        if delay>0:time.sleep(delay)
        tasks.append(ex.submit(send))
    results=[f.result() for f in tasks]
latencies=sorted(ms for status,ms in results)
p95=latencies[min(len(latencies)-1,int(len(latencies)*0.95))]
errors=sum(status!=201 for status,_ in results)
report=dict(requests=len(results),targetRps=a.rate,elapsedSeconds=round(time.monotonic()-start,3),p95Ms=round(p95,3),errors=errors,targetMet=errors==0 and p95<500)
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['targetMet'] else 1)
