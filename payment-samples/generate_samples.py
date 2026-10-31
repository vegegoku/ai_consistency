#!/usr/bin/env python3
"""Generate deterministic JSON/XML payment message corpora and their fixture data."""

from __future__ import annotations

import csv
import json
import shutil
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE_TIME = datetime(2026, 10, 31, 9, 0, tzinfo=timezone.utc)
NAMESPACE = uuid.UUID("e5f572e5-394d-5b3f-8d85-c7c9283a7fe1")

ACCOUNTS = [
    {"accountId": "ACC-1001", "customerId": "customer-01", "displayName": "Sample Sender", "currency": "JOD", "availableBalance": "500.000", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-2002", "customerId": "customer-02", "displayName": "Sample Recipient", "currency": "JOD", "availableBalance": "100.000", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-3003", "customerId": "customer-03", "displayName": "Zero Balance", "currency": "JOD", "availableBalance": "0.000", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-4004", "customerId": "customer-04", "displayName": "Maximum Transfer Sender", "currency": "JOD", "availableBalance": "10000.000", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-5005", "customerId": "customer-05", "displayName": "Small Balance", "currency": "JOD", "availableBalance": "0.001", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-6006", "customerId": "customer-06", "displayName": "Alternate Sender", "currency": "JOD", "availableBalance": "2500.125", "status": "ACTIVE", "canDebit": True, "canReceive": True},
    {"accountId": "ACC-7007", "customerId": "customer-07", "displayName": "Frozen Account", "currency": "JOD", "availableBalance": "750.000", "status": "FROZEN", "canDebit": False, "canReceive": False},
    {"accountId": "ACC-8008", "customerId": "customer-08", "displayName": "Closed Account", "currency": "JOD", "availableBalance": "25.000", "status": "CLOSED", "canDebit": False, "canReceive": False},
    {"accountId": "ACC-9009", "customerId": "customer-09", "displayName": "Receive Restricted", "currency": "JOD", "availableBalance": "50.000", "status": "ACTIVE", "canDebit": True, "canReceive": False},
    {"accountId": "ACC-1010", "customerId": "customer-10", "displayName": "Debit Restricted", "currency": "JOD", "availableBalance": "800.000", "status": "ACTIVE", "canDebit": False, "canReceive": True},
]

def payment(i: int) -> dict:
    return {
        "paymentId": str(uuid.uuid5(NAMESPACE, f"payment-{i:03d}")),
        "fromAccount": "ACC-1001",
        "toAccount": "ACC-2002",
        "amount": "12.345",
        "date": "2026-10-31T12:00:00+03:00",
        "purpose": f"Sample transfer {i:03d}",
    }

def scenario(name: str, expected: str, code: str = "", change: dict | None = None, raw_json: str | None = None, raw_xml: str | None = None) -> dict:
    return {"name": name, "expected": expected, "code": code, "change": change or {}, "raw_json": raw_json, "raw_xml": raw_xml}

CASES: list[dict] = []

# Happy paths and amount/date/optional-field boundaries.
for j, (label, amount) in enumerate([
    ("minimum positive amount", "0.001"), ("one dinar", "1.000"), ("integer decimal", "1"),
    ("two fractional digits", "1.20"), ("three fractional digits", "1.234"),
    ("maximum allowed amount", "10000.000"), ("small fractional transfer", "0.010"),
    ("empty optional notes", "2.000"), ("maximum typical balance transfer", "500.000"),
    ("alternate active source", "25.125"), ("UTC timestamp", "3.000"),
    ("positive offset timestamp", "4.000"), ("negative offset timestamp", "5.000"),
    ("trim-safe purpose", "6.000"), ("maximum length purpose", "7.000"),
    ("maximum length notes", "8.000"), ("unicode descriptive text", "9.000"),
    ("xml/json equivalent amount", "125.750"), ("exact source balance", "250.000"),
], 1):
    change = {"amount": amount}
    if label == "maximum allowed amount": change["fromAccount"] = "ACC-4004"
    if label == "alternate active source": change.update(fromAccount="ACC-6006")
    if label == "empty optional notes": change["notes"] = ""
    if label == "UTC timestamp": change["date"] = "2026-10-31T09:00:00Z"
    if label == "positive offset timestamp": change["date"] = "2026-10-31T14:00:00+05:00"
    if label == "negative offset timestamp": change["date"] = "2026-10-31T04:00:00-05:00"
    if label == "trim-safe purpose": change["purpose"] = "  invoice settlement  "
    if label == "maximum length purpose": change["purpose"] = "P" * 255
    if label == "maximum length notes": change["notes"] = "N" * 1000
    if label == "unicode descriptive text": change.update(purpose="فاتورة خدمات – 東京", notes="café ✓")
    if label == "exact source balance": change.update(fromAccount="ACC-6006", amount="2500.125")
    CASES.append(scenario(f"valid-{label.replace(' ', '-')}", "201 COMPLETED", change=change))

# Required-field presence, null, blank, and wrong-type checks.
for field in ("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose"):
    CASES.append(scenario(f"missing-{field}", "400 VALIDATION_FAILED", "VALIDATION_FAILED", {field: "__MISSING__"}))
    CASES.append(scenario(f"null-{field}", "400 VALIDATION_FAILED", "VALIDATION_FAILED", {field: None}))
for field in ("fromAccount", "toAccount", "purpose"):
    CASES.append(scenario(f"blank-{field}", "400 VALIDATION_FAILED", "VALIDATION_FAILED", {field: "   "}))
for field, value in [("paymentId", 123), ("fromAccount", 1001), ("toAccount", None), ("amount", True), ("date", 1700000000), ("purpose", ["invoice"])]:
    CASES.append(scenario(f"wrong-type-{field}", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", {field: value}))

# Field formats, amount rules, account identifiers, and length constraints.
for name, field, value, result, code in [
    ("invalid-uuid", "paymentId", "not-a-uuid", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("uuid-without-hyphens", "paymentId", "d92192dff79a455381205660d2eb1294", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("zero-amount", "amount", "0.000", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("negative-amount", "amount", "-0.001", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("excess-precision", "amount", "1.0001", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("over-configured-maximum", "amount", "10000.001", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("huge-amount", "amount", "999999999999999999999999999", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("non-numeric-amount", "amount", "twelve", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE"),
    ("amount-exponent", "amount", "1e2", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("same-account", "toAccount", "ACC-1001", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("unknown-source", "fromAccount", "ACC-4040", "404 ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_FOUND"),
    ("unknown-destination", "toAccount", "ACC-4041", "404 ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_FOUND"),
    ("account-id-whitespace", "fromAccount", " ACC-1001 ", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("purpose-too-long", "purpose", "P" * 256, "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("notes-too-long", "notes", "N" * 1001, "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("notes-null", "notes", None, "201 COMPLETED", ""),
    ("invalid-date", "date", "yesterday", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("date-without-offset", "date", "2026-10-31T12:00:00", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("date-impossible-day", "date", "2026-02-30T12:00:00Z", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("date-too-old", "date", "2026-09-30T08:59:59Z", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("date-too-far-future", "date", "2026-10-31T09:06:00Z", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
    ("date-at-30-day-boundary", "date", "2026-10-01T09:00:00Z", "201 COMPLETED", ""),
    ("date-at-5-minute-boundary", "date", "2026-10-31T09:05:00Z", "201 COMPLETED", ""),
    ("date-leap-day", "date", "2024-02-29T12:00:00Z", "400 VALIDATION_FAILED", "VALIDATION_FAILED"),
]:
    CASES.append(scenario(name, result, code, {field: value}))

# Business outcomes with fixture accounts.
for name, field, value, result, code in [
    ("frozen-source", "fromAccount", "ACC-7007", "409 ACCOUNT_NOT_ACTIVE", "ACCOUNT_NOT_ACTIVE"),
    ("closed-source", "fromAccount", "ACC-8008", "409 ACCOUNT_NOT_ACTIVE", "ACCOUNT_NOT_ACTIVE"),
    ("frozen-destination", "toAccount", "ACC-7007", "409 ACCOUNT_NOT_ACTIVE", "ACCOUNT_NOT_ACTIVE"),
    ("closed-destination", "toAccount", "ACC-8008", "409 ACCOUNT_NOT_ACTIVE", "ACCOUNT_NOT_ACTIVE"),
    ("insufficient-funds", "fromAccount", "ACC-3003", "409 INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS"),
    ("one-mill-over-balance", "amount", "500.001", "409 INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS"),
    ("receive-restricted-destination", "toAccount", "ACC-9009", "409 BUSINESS_REJECTED", "BUSINESS_REJECTED"),
    ("debit-restricted-source", "fromAccount", "ACC-1010", "403 FORBIDDEN", "FORBIDDEN"),
]:
    CASES.append(scenario(name, result, code, {field: value}))

# Structural, ambiguity, and XML security cases. Raw inputs intentionally stay raw.
CASES += [
    scenario("empty-body", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json="", raw_xml=""),
    scenario("json-truncated", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json='{"paymentId":'),
    scenario("json-trailing-comma", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json='{"paymentId":"x",}'),
    scenario("json-duplicate-paymentId", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json='{"paymentId":"d92192df-f79a-4553-8120-5660d2eb1294","paymentId":"d92192df-f79a-4553-8120-5660d2eb1294"}'),
    scenario("json-unknown-property", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", {"unexpected": "value"}),
    scenario("json-top-level-array", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json="[]"),
    scenario("json-top-level-string", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json='"payment"'),
    scenario("xml-truncated", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml="<payment><amount>1"),
    scenario("xml-wrong-root", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml="<transfer/>") ,
    scenario("xml-duplicate-element", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml="<payment><paymentId>" + str(uuid.uuid5(NAMESPACE, "duplicate")) + "</paymentId><paymentId>" + str(uuid.uuid5(NAMESPACE, "duplicate")) + "</paymentId></payment>"),
    scenario("xml-unknown-element", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", {"__unknown_xml__": "unexpected"}),
    scenario("xml-doctype", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml='<!DOCTYPE payment [<!ENTITY x "blocked">]><payment><purpose>&x;</purpose></payment>'),
    scenario("xml-external-entity-file", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml='<!DOCTYPE payment [<!ENTITY x SYSTEM "file:///etc/passwd">]><payment><purpose>&x;</purpose></payment>'),
    scenario("xml-external-entity-network", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml='<!DOCTYPE payment [<!ENTITY x SYSTEM "http://127.0.0.1:9/xxe">]><payment><purpose>&x;</purpose></payment>'),
    scenario("xml-xinclude", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml='<payment xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="file:///etc/passwd" parse="text"/></payment>'),
    scenario("xml-nested-depth", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_xml="<payment>" + "<x>"*80 + "x" + "</x>"*80 + "</payment>"),
    scenario("json-over-64k", "413 REQUEST_TOO_LARGE", "REQUEST_TOO_LARGE", raw_json='{"notes":"' + "x"*65540 + '"}'),
    scenario("xml-over-64k", "413 REQUEST_TOO_LARGE", "REQUEST_TOO_LARGE", raw_xml="<payment><notes>" + "x"*65540 + "</notes></payment>"),
    scenario("json-invalid-utf8-marker", "400 MALFORMED_MESSAGE", "MALFORMED_MESSAGE", raw_json="\ufffd"),
]

# Retry/idempotency examples. Replays deliberately share an ID.
replay_id = str(uuid.uuid5(NAMESPACE, "idempotent-replay"))
for name, change in [("idempotency-first-request", {}), ("idempotency-identical-replay", {}), ("idempotency-conflicting-amount", {"amount": "13.345"}), ("idempotency-conflicting-recipient", {"toAccount": "ACC-6006"})]:
    CASES.append(scenario(name, "201/200 COMPLETED" if not change else "409 IDEMPOTENCY_CONFLICT", "" if not change else "IDEMPOTENCY_CONFLICT", {"__payment_id__": replay_id, "purpose": "Shared idempotency fixture", **change}))

# Validation aggregation: one payload contains several safe field errors.
for i, change in enumerate([
    {"paymentId": "bad", "amount": "0", "date": "bad", "purpose": ""},
    {"fromAccount": "", "toAccount": "", "amount": "-1"},
    {"amount": "1.1234", "date": "2026-10-31T12:00:00", "purpose": " "},
    {"paymentId": None, "fromAccount": None, "toAccount": None, "amount": None, "date": None, "purpose": None},
    {"amount": "10000.001", "purpose": "P"*256, "notes": "N"*1001},
], 1):
    CASES.append(scenario(f"aggregated-validation-{i}", "400 VALIDATION_FAILED", "VALIDATION_FAILED", change))

# Enough independent valid permutations to reach exactly 250 distinct cases.
while len(CASES) < 250:
    i = len(CASES) + 1
    idx = i - 1
    source, dest = [("ACC-1001", "ACC-2002"), ("ACC-6006", "ACC-2002"), ("ACC-4004", "ACC-1001"), ("ACC-2002", "ACC-6006")][idx % 4]
    amount = ["0.001", "0.010", "0.125", "1.000", "12.345", "99.999", "125.750", "500.000"][idx % 8]
    CASES.append(scenario(f"valid-permutation-{i:03d}", "201 COMPLETED", change={
        "fromAccount": source, "toAccount": dest, "amount": amount,
        "purpose": f"Batch sample {i:03d} – {['invoice','services','rent','refund'][idx % 4]}",
        "date": (BASE_TIME + timedelta(seconds=idx)).isoformat(),
        "notes": "" if idx % 5 == 0 else f"Reference {i:03d}",
    }))
CASES = CASES[:250]

def render_json(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"

def render_xml(obj: dict) -> str:
    root = ET.Element("payment")
    for key in ("paymentId", "fromAccount", "toAccount", "amount", "date", "purpose", "notes"):
        if key in obj:
            node = ET.SubElement(root, key)
            if obj[key] is not None:
                node.text = str(obj[key])
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n"

def make_payload(base: dict, change: dict, fmt: str, raw: str | None) -> str:
    if raw is not None: return raw + ("\n" if raw and not raw.endswith("\n") else "")
    obj = dict(base)
    for key, value in change.items():
        if key == "__payment_id__": obj["paymentId"] = value
        elif key == "__unknown_xml__": continue
        elif value == "__MISSING__": obj.pop(key, None)
        else: obj[key] = value
    if fmt == "json" and "__unknown_xml__" in change: obj["unexpected"] = change["__unknown_xml__"]
    if fmt == "xml" and "__unknown_xml__" in change:
        rawxml = render_xml(obj).rstrip()
        return rawxml.replace("</payment>", "<unexpected>value</unexpected></payment>") + "\n"
    return render_json(obj) if fmt == "json" else render_xml(obj)

def main() -> None:
    for fmt in ("json", "xml"):
        folder = ROOT / fmt
        if folder.exists(): shutil.rmtree(folder)
        folder.mkdir(parents=True)
    (ROOT / "accounts.json").write_text(json.dumps({
        "fixtureName": "payment-samples-10-accounts-v1",
        "currency": "JOD",
        "defaultAuthorizedPrincipal": "sample-client",
        "accounts": ACCOUNTS,
        "note": "Balances and statuses are reset before each independent sample unless a test explicitly covers replay or concurrency."
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = []
    for i, case in enumerate(CASES, 1):
        base = payment(i)
        for fmt in ("json", "xml"):
            raw = case[f"raw_{fmt}"]
            body = make_payload(base, case["change"], fmt, raw)
            suffix = "json" if fmt == "json" else "xml"
            safe_name = case["name"].replace("/", "-")
            filename = f"{i:03d}-{safe_name}.{suffix}"
            (ROOT / fmt / filename).write_text(body, encoding="utf-8")
        expected_json = "201 COMPLETED" if case["raw_xml"] is not None and case["raw_json"] is None else case["expected"]
        expected_xml = "201 COMPLETED" if case["raw_json"] is not None and case["raw_xml"] is None else case["expected"]
        rows.append({
            "sampleNumber": f"{i:03d}", "case": case["name"],
            "jsonFile": f"json/{i:03d}-{safe_name}.json",
            "xmlFile": f"xml/{i:03d}-{safe_name}.xml",
            "expectedJsonOutcome": expected_json, "expectedJsonCode": case["code"] if expected_json == case["expected"] else "",
            "expectedXmlOutcome": expected_xml, "expectedXmlCode": case["code"] if expected_xml == case["expected"] else "",
            "execution": "Independent fixture reset; replay/concurrency cases require grouped requests",
            "notes": ("This validation is format-specific; counterpart sample is a valid message." if (case["raw_json"] is not None) != (case["raw_xml"] is not None) else "") + (" Authorization and idempotency cases require corresponding caller or grouped-request setup." if any(x in case["name"] for x in ("restricted", "idempotency")) else "")
        })
    with (ROOT / "manifest.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader(); writer.writerows(rows)

if __name__ == "__main__":
    main()
