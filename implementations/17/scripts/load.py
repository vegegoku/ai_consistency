#!/usr/bin/env python3
"""Fixed arrival-rate HTTP transfer benchmark. Use funded isolated fixtures only."""
import argparse
import concurrent.futures
import datetime
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', default='http://localhost:8080')
parser.add_argument('--source', default='ACC-1001')
parser.add_argument('--destination', default='ACC-2002')
parser.add_argument('--rate', type=int, default=100)
parser.add_argument('--seconds', type=int, default=30)
parser.add_argument('--report', default='target/load-report.json')
args = parser.parse_args()
token = os.environ['TOKEN']
if args.rate <= 0 or args.seconds <= 0:
    parser.error('rate and seconds must be positive')


def transfer(planned):
    instruction = {'paymentId': str(uuid.uuid4()), 'fromAccount': args.source,
                   'toAccount': args.destination, 'amount': '0.001',
                   'date': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'purpose': 'Isolated performance measurement'}
    request = urllib.request.Request(args.url.rstrip('/') + '/api/v1/payments',
        data=json.dumps(instruction).encode(), method='POST',
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except (OSError, TimeoutError):
        status = 0
    return status, (time.perf_counter() - planned) * 1000


start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=max(32, args.rate * 2)) as pool:
    futures = []
    for index in range(args.rate * args.seconds):
        planned = start + index / args.rate
        delay = planned - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        futures.append(pool.submit(transfer, planned))
    results = [future.result() for future in futures]
latencies = sorted(latency for _, latency in results)
statuses = {}
for status, _ in results:
    statuses[str(status)] = statuses.get(str(status), 0) + 1
report = {'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'offered_rps': args.rate, 'duration_seconds': args.seconds,
          'requests': len(results), 'statuses': statuses,
          'p95_ms': latencies[math.ceil(len(latencies) * .95) - 1],
          'max_ms': max(latencies), 'elapsed_seconds': time.perf_counter() - start,
          'latency_includes_scheduling_delay': True}
os.makedirs(os.path.dirname(args.report) or '.', exist_ok=True)
with open(args.report, 'w') as output:
    json.dump(report, output, indent=2)
print(json.dumps(report, indent=2))
raise SystemExit(0 if statuses == {'201': len(results)} and report['p95_ms'] < 500 else 1)
