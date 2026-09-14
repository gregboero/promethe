"""Archive the Koog protocol comparison and reconcile every paid attempt before reporting."""
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
parser.add_argument("--before", default="build/reports/harness-koog-protocol/before.json")
parser.add_argument("--report-root", default="docs/reports/harness-koog-protocol-data-2026-09-07")
parser.add_argument("--validation-prefix", default="harness-koog-protocol")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
live = root / "build/reports/harness-iteration/live"
source = (live / args.batch).resolve()
assert source.parent == live.resolve() and source.name.startswith("koog-protocol-")
protocol = json.loads((source / "protocol.json").read_text(encoding="utf-8"))
results = json.loads((source / "results.json").read_text(encoding="utf-8"))
before = json.loads((root / args.before).read_text(encoding="utf-8"))
assert all(hashlib.sha256((root / p).read_bytes()).hexdigest() == h for p, h in before["sourceSha256"].items())
inputs = [(p, json.loads(p.read_text(encoding="utf-8"))) for p in live.glob("request-*.json")]
inputs = [(p, r) for p, r in inputs if r["label"].startswith(source.name + "-")]
receipts = [r for _, r in inputs]
with sqlite3.connect(f"file:{(live / 'campaign-budget.sqlite').as_posix()}?mode=ro", uri=True) as db:
    budget = db.execute("SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id").fetchall()
old = {row[0]: tuple(row) for row in before["budgetRows"]}
added = [r for r in budget if r[0] not in old]
assert len(budget) == len(old) + len(added)
assert all(old[r[0]] == r for r in budget if r[0] in old)
assert {r[0] for r in added} == {r["id"] for r in receipts}
assert len(receipts) <= 36 and sum(c if s else r for _, r, c, s in budget) <= 5_000_000
account = runpy.run_path(str(root / "scripts/harness-receipt-accounting.py"))["summarize"]
for row in results:
    calls = [r for r in receipts if r["label"].startswith(row["session"] + "-call-")]
    calls.sort(key=lambda r: int(r["label"].rsplit("-call-", 1)[1]))
    row["receiptAccounting"] = account(calls)
    row["apiMillis"] = sum(r["elapsedMillis"] for r in calls)
    assert all(r["toolDefinitions"] == (1 if row["mode"] == "structured" else 0) for r in calls)
    assert all(r["model"] == "gpt-5.6-terra" and r["endpoint"] == "responses" and r["reasoningEffort"] == "none" and r["maxOutputTokens"] == 4096 for r in calls)
    if protocol.get("nativeHistory") == "execution-local-chronological-v1":
        prior_native_calls = 0
        for receipt in calls:
            assert receipt["nativeHistoryPairsValid"]
            assert receipt["toolResultInputs"] == prior_native_calls
            assert len(receipt["nativeHistoryPages"]) == prior_native_calls
            prior_native_calls += receipt["functionCalls"]
        row["nativeHistoryFullyCarried"] = True
first = [r for r in receipts if r["label"].endswith("-call-1")]
assert len({r["inputSha256"] for r in first}) <= 1
summary = dict(batch=source.name, plannedRuns=6, recordedRuns=len(results), completeCohort=len(results)==6,
               correctRuns=sum(r["correct"] for r in results), completedRuns=sum(r["completed"] for r in results),
               successfulRuns=sum(r["correct"] and r["completed"] for r in results),
               failureCodes=dict(Counter(r["failure"] for r in results if r["failure"])),
               accounting=account(receipts), initialInputHashesIdentical=True,
               modelEndpointEffortBoundsVerified=True,
               httpStatuses=dict(Counter(str(r["httpStatus"]) for r in receipts)),
               responseStatuses=dict(Counter(str(r["responseStatus"]) for r in receipts)),
               nativeFunctionCalls=sum(r["functionCalls"] for r in receipts),
               functionResultInputs=sum(r["toolResultInputs"] for r in receipts),
               allRegistriesCleaned=bool(results) and all(r["registryCleaned"] for r in results),
               campaignBefore=dict(requests=len(old), settledMicroUsd=sum(r[2] for r in old.values() if r[3])),
               campaignAfter=dict(requests=len(budget), settledMicroUsd=sum(c for _, _, c, s in budget if s),
                                  uncertainReservations=sum(not s for _, _, _, s in budget),
                                  remainingMicroUsd=5_000_000-sum(c if s else r for _, r, c, s in budget)),
               priorBudgetRowsUnchanged=True, validation={}, sourceSha256=before["sourceSha256"])
summary["sourceSha256"]["scripts/report-harness-koog-protocol.py"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
summary["measurementInputSha256"] = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*source.glob("*.json"), *(p for p, _ in inputs)]}
target = root / args.report_root / source.name
target.mkdir(parents=True, exist_ok=True)
for module, task in [("api", "jvmTest"), ("shared", "jvmTest"), ("gateway", "test"), ("evals", "test"), ("composeApp", "desktopTest"), ("shared", "harnessLiveTest")]:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (root / module / "build/test-results" / task).glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for field in totals:
            totals[field] += int(suite.attrib.get(field, 0))
        if "Koog" in path.name:
            shutil.copy2(path, target / f"{task}-{path.name}")
    summary["validation"][f"{module}:{task}"] = totals
for name in (f"{args.validation_prefix}-final-validation.log", f"{args.validation_prefix}-live.log"):
    shutil.copy2(root / "build/reports" / name, target / name)
for path in source.glob("*-transcript.json"):
    shutil.copy2(path, target / path.name)
for name, value in (("protocol", protocol), ("summary", summary), ("results", results), ("requests", receipts)):
    (target / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if not k.endswith("Sha256")}, indent=2))
