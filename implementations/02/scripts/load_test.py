#!/usr/bin/env python3
"""Dependency-free, fixed-arrival-rate HTTP load test; use only dedicated funded fixtures."""
import concurrent.futures, datetime, json, math, os, pathlib, time, urllib.request, urllib.error, uuid
rate = int(os.environ.get('RATE', '100'))
seconds = int(os.environ.get('SECONDS', '60'))
base = os.environ.get('BASE_URL', 'https://localhost:8443')
token = os.environ['TOKEN']
def transfer(index):
    body = json.dumps({'paymentId':str(uuid.uuid4()), 'fromAccount':os.environ.get('FROM_ACCOUNT','ACC-1001'),
        'toAccount':os.environ.get('TO_ACCOUNT','ACC-2002'), 'amount':'0.001',
        'date':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'purpose':'Performance fixture'}).encode()
    request = urllib.request.Request(base+'/api/v1/payments', body, {'Authorization':'Bearer '+token, 'Content-Type':'application/json'})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status = response.status
            receipt = json.load(response)
            valid = status == 201 and receipt.get('amount') == '0.001' and receipt.get('status') == 'COMPLETED'
    except urllib.error.HTTPError as error:
        status, valid = error.code, False
    except Exception:
        status, valid = 0, False
    return (time.perf_counter()-started)*1000, status, valid
start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
    work = []
    for index in range(rate*seconds):
        delay = start+index/rate-time.perf_counter()
        if delay > 0: time.sleep(delay)
        work.append(pool.submit(transfer,index))
    results = [item.result() for item in work]
latencies = sorted(item[0] for item in results)
statuses = {}
for _, status, _ in results: statuses[str(status)] = statuses.get(str(status), 0)+1
p95 = latencies[math.ceil(len(latencies)*.95)-1]
report = {'requests':len(results), 'targetRps':rate, 'arrivalSeconds':seconds, 'wallSeconds':round(time.perf_counter()-start,3),
          'p95Ms':round(p95,3), 'maxMs':round(max(latencies),3), 'successfulReceipts':sum(item[2] for item in results), 'statuses':statuses}
report['passed'] = p95 < 500 and report['successfulReceipts']/len(results) > .99
path = pathlib.Path(os.environ.get('REPORT','target/load-report.json'))
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['passed'] else 1)
