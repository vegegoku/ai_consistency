#!/usr/bin/env python3
"""Submit the payment sample corpus sequentially and persist one result per request."""

from __future__ import annotations

import argparse
import csv
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def response_summary(raw: bytes) -> tuple[str, str, str]:
    text = raw.decode("utf-8", errors="replace")
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return text, str(obj.get("status", "")), str(obj.get("code", ""))
    except (json.JSONDecodeError, ValueError):
        pass
    return text, "", ""

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--implementation", required=True, help="Implementation number, e.g. 01")
    parser.add_argument("--base-url", required=True, help="Service origin, including scheme and port")
    parser.add_argument("--token", default=os.environ.get("PAYMENT_TOKEN", ""), help="Bearer token (or set PAYMENT_TOKEN)")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "results")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--start", type=int, default=1, help="First sample number, inclusive")
    parser.add_argument("--limit", type=int, default=250, help="Number of samples to process")
    parser.add_argument("--formats", nargs="+", choices=("json", "xml"), default=("json", "xml"))
    parser.add_argument("--insecure-tls", action="store_true", help="Accept a local self-signed certificate")
    args = parser.parse_args()
    if not args.token:
        parser.error("provide --token or PAYMENT_TOKEN")

    with (ROOT / "manifest.csv").open(newline="", encoding="utf-8") as f:
        manifest = list(csv.DictReader(f))
    selected = [r for r in manifest if args.start <= int(r["sampleNumber"]) < args.start + args.limit]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    out = args.output_dir / f"implementation-{args.implementation}.jsonl"
    tls_context = ssl._create_unverified_context() if args.insecure_tls else ssl.create_default_context()
    summary_http: Counter[str] = Counter()
    summary_code: Counter[str] = Counter()
    completed = 0

    with out.open("w", encoding="utf-8", buffering=1) as log:
        for row in selected:
            for fmt in args.formats:
                rel = row["jsonFile"] if fmt == "json" else row["xmlFile"]
                body = (ROOT / rel).read_bytes()
                request = urllib.request.Request(
                    args.base_url.rstrip("/") + "/api/v1/payments",
                    data=body,
                    method="POST",
                    headers={
                        "Authorization": f"Bearer {args.token}",
                        "Content-Type": "application/json" if fmt == "json" else "application/xml",
                        "Accept": "application/json",
                        "Connection": "close",
                    },
                )
                started = time.monotonic()
                status: int | None = None
                response_body = ""
                payment_status = ""
                code = ""
                transport_error = ""
                try:
                    with urllib.request.urlopen(request, timeout=args.timeout, context=tls_context) as response:
                        status = response.status
                        raw = response.read()
                    response_body, payment_status, code = response_summary(raw)
                except urllib.error.HTTPError as exc:
                    status = exc.code
                    try:
                        raw = exc.read()
                    except Exception:
                        raw = b""
                    response_body, payment_status, code = response_summary(raw)
                except Exception as exc:  # A failed request is recorded and processing continues.
                    transport_error = f"{type(exc).__name__}: {exc}"
                elapsed_ms = round((time.monotonic() - started) * 1000, 3)
                actual_payment_id = ""
                try:
                    if fmt == "json":
                        raw_request = json.loads(body)
                        if isinstance(raw_request, dict):
                            actual_payment_id = str(raw_request.get("paymentId", ""))
                    else:
                        root = ET.fromstring(body)
                        payment_id = root.find("paymentId")
                        actual_payment_id = payment_id.text if payment_id is not None and payment_id.text else ""
                except (json.JSONDecodeError, ET.ParseError, ValueError):
                    pass
                result = {
                    "implementation": args.implementation,
                    "sampleNumber": row["sampleNumber"],
                    "case": row["case"],
                    "format": fmt,
                    "file": rel,
                    "paymentId": actual_payment_id,
                    "httpStatus": status,
                    "paymentStatus": payment_status,
                    "code": code,
                    "elapsedMs": elapsed_ms,
                    "expectedOutcome": row["expectedJsonOutcome" if fmt == "json" else "expectedXmlOutcome"],
                    "transportError": transport_error,
                    "response": response_body,
                    "recordedAt": datetime.now(timezone.utc).isoformat(),
                }
                log.write(json.dumps(result, ensure_ascii=False) + "\n")
                completed += 1
                summary_http[str(status) if status is not None else "transport-error"] += 1
                if code:
                    summary_code[code] += 1
                print(f"{args.implementation} {row['sampleNumber']} {fmt.upper()} HTTP={status if status is not None else 'ERR'} status={payment_status or '-'} code={code or '-'} ms={elapsed_ms:.1f}", flush=True)

    summary = {
        "implementation": args.implementation,
        "baseUrl": args.base_url,
        "samplesPerFormat": len(selected),
        "formats": args.formats,
        "requestsRecorded": completed,
        "httpStatusCounts": dict(summary_http),
        "errorCodeCounts": dict(summary_code),
        "messageResults": out.name,
        "completedAt": datetime.now(timezone.utc).isoformat(),
    }
    summary_path = args.output_dir / f"implementation-{args.implementation}-summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0

if __name__ == "__main__":
    sys.exit(main())
