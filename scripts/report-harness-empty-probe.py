"""Reconcile one minimal response probe with exact request hashes and the shared budget."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("batch")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
live = root / "build/reports/harness-iteration/live"
source = (live / args.batch).resolve()
assert source.parent == live.resolve() and source.name.startswith("empty-probe-")
protocol = json.loads((source / "protocol.json").read_text(encoding="utf-8"))
results = json.loads((source / "results.json").read_text(encoding="utf-8"))
before = json.loads((root / "build/reports/harness-empty-probe/before.json").read_text(encoding="utf-8"))
assert all(hashlib.sha256((root / p).read_bytes()).hexdigest() == h for p, h in before["sourceSha256"].items())
receipt_inputs = [(p, json.loads(p.read_text(encoding="utf-8"))) for p in live.glob("request-*.json")]
receipt_inputs = [(p, r) for p, r in receipt_inputs if r["label"].startswith(source.name + "-")]
receipts = [r for _, r in receipt_inputs]
assert len(receipts) <= 15 and protocol["plannedCalls"] == 15
with sqlite3.connect(f"file:{(live / 'campaign-budget.sqlite').as_posix()}?mode=ro", uri=True) as db:
    budget = db.execute("SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id").fetchall()
old = {row[0]: tuple(row) for row in before["budgetRows"]}
added = [row for row in budget if row[0] not in old]
assert len(budget) == len(old) + len(added)
assert all(old[row[0]] == row for row in budget if row[0] in old)
assert {row[0] for row in added} == {r["id"] for r in receipts}
assert sum(c if s else r for _, r, c, s in budget) <= 5_000_000
account = runpy.run_path(str(root / "scripts/harness-receipt-accounting.py"))["summarize"]
variants = {v["name"]: v for v in protocol["variants"]}
for row in results:
    receipt = next(r for r in receipts if r["label"] == row["label"])
    variant = variants[row["variant"]]
    payload = {"model": protocol["model"]}
    if variant["reasoningEffort"] != "omitted":
        payload["reasoning_effort"] = variant["reasoningEffort"]
    payload.update(store=False, max_completion_tokens=4096, stream=False)
    payload["messages"] = [{"role": "user" if variant["history"] is None else "system", "content": variant["prompt"]}] + (variant["history"] or [])
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    assert hashlib.sha256(encoded).hexdigest() == receipt["requestSha256"]
    assert len(encoded) == receipt["requestBytes"]
    audit = row["wireAudit"]
    diagnostics = receipt.get("diagnostics") or {}
    if audit is not None:
        assert audit["emptyText"] == (diagnostics.get("contentState") == "empty")
        if row["response"] is not None:
            assert len(row["response"]) == audit["contentCodePoints"]
    row["receipt"] = receipt
metrics = []
for name in variants:
    rows = [r for r in results if r["variant"] == name]
    metrics.append(dict(variant=name, calls=len(rows), expected=sum(r["matchesExpectedFirstResponse"] for r in rows),
                        empty=sum(r["errorCode"] == "campaign_empty_content" for r in rows),
                        errors=dict(Counter(r["errorCode"] for r in rows if r["errorCode"])),
                        reasoningTokens=[r["receipt"]["diagnostics"]["reasoningTokens"] for r in rows],
                        accounting=account([r["receipt"] for r in rows])))
summary = dict(batch=source.name, recordedCalls=len(results), plannedCalls=15,
               expectedFirstResponses=sum(r["matchesExpectedFirstResponse"] for r in results),
               responseErrors=dict(Counter(r["errorCode"] for r in receipts if r["errorCode"])),
               finishReasons=dict(Counter(str((r.get("diagnostics") or {}).get("finishReason")) for r in receipts)),
               independentWireAudits=sum(r["wireAudit"] is not None for r in results),
               requestPayloadHashesVerified=True, independentDecodersAgree=True,
               accounting=account(receipts), metrics=metrics,
               campaignBefore=dict(requests=len(old), settledMicroUsd=sum(r[2] for r in old.values() if r[3])),
               campaignAfter=dict(requests=len(budget), settledMicroUsd=sum(c for _, _, c, s in budget if s),
                                  uncertainReservations=sum(not s for _, _, _, s in budget),
                                  remainingMicroUsd=5_000_000-sum(c if s else r for _, r, c, s in budget)),
               priorBudgetRowsUnchanged=True, toolsExecuted=0, nativeWorkers=0,
               sourceSha256=before["sourceSha256"], validation={})
summary["sourceSha256"]["scripts/report-harness-empty-probe.py"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
summary["measurementInputSha256"] = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in [*source.glob("*.json"), *(p for p, _ in receipt_inputs)]}
target = root / "docs/reports/harness-empty-probe-data-2026-09-07" / source.name
target.mkdir(parents=True, exist_ok=True)
for task in ("jvmTest", "harnessLiveTest"):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (root / "shared/build/test-results" / task).glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for field in totals:
            totals[field] += int(suite.attrib.get(field, 0))
        shutil.copy2(path, target / f"{task}-{path.name}")
    summary["validation"][f"shared:{task}"] = totals
for name in ("harness-empty-probe-validation.log", "harness-empty-probe-live.log"):
    shutil.copy2(root / "build/reports" / name, target / name)
for name, value in (("protocol", protocol), ("summary", summary), ("results", results), ("requests", receipts)):
    (target / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if not k.endswith("Sha256")}, indent=2))
