"""Archive one six-run diagnostic pilot without changing earlier cohorts or the shared budget."""
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
assert source.parent == live.resolve() and source.name.startswith("kotlin-exposure-")
protocol = json.loads((source / "protocol.json").read_text(encoding="utf-8"))
assert protocol["protocolVersion"] == 6 and protocol["diagnosticPilot"] is True and protocol["plannedRuns"] == 6
before = json.loads((root / "build/reports/harness-diagnostic-pilot/before.json").read_text(encoding="utf-8"))
assert all(hashlib.sha256((root / name).read_bytes()).hexdigest() == digest for name, digest in before["sourceSha256"].items())
results = json.loads((source / "results.json").read_text(encoding="utf-8"))
receipt_inputs = [(p, json.loads(p.read_text(encoding="utf-8"))) for p in live.glob("request-*.json")]
receipt_inputs = [(p, r) for p, r in receipt_inputs if r["label"].startswith(source.name + "-")]
receipts = sorted([r for _, r in receipt_inputs], key=lambda r: r["label"])
assert all(r["receiptVersion"] == 2 for r in receipts)
with sqlite3.connect(f"file:{(live / 'campaign-budget.sqlite').as_posix()}?mode=ro", uri=True) as db:
    budget = db.execute("SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id").fetchall()
old_rows = {row[0]: tuple(row) for row in before["budgetRows"]}
new_rows = [row for row in budget if row[0] not in old_rows]
assert all(old_rows[row[0]] == row for row in budget if row[0] in old_rows)
assert len(budget) == len(old_rows) + len(new_rows)
assert {row[0] for row in new_rows} == {r["id"] for r in receipts}
assert sum(c if s else r for _, r, c, s in budget) <= 5_000_000
account = runpy.run_path(str(root / "scripts/harness-receipt-accounting.py"))["summarize"]
for row in results:
    calls = [r for r in receipts if r["label"].startswith(row["session"] + "-call-")]
    row["receiptAccounting"] = account(calls)
    row["responseErrors"] = dict(Counter(r["errorCode"] for r in calls if r["errorCode"]))
summary = {
    "batch": source.name,
    "protocolVersion": 6,
    "plannedRuns": 6,
    "recordedRuns": len(results),
    "completeCohort": len(results) == 6,
    "completedRuns": sum(row["completed"] for row in results),
    "structurallyCompleteRuns": sum(row["completion"]["complete"] for row in results),
    "correctRuns": sum(row["correct"] for row in results),
    "failureCodes": dict(Counter(row["failureCode"] for row in results if row["failureCode"])),
    "completionReasons": dict(Counter(row["completion"]["reason"] for row in results)),
    "responseErrors": dict(Counter(r["errorCode"] for r in receipts if r["errorCode"])),
    "finishReasons": dict(Counter(str((r.get("diagnostics") or {}).get("finishReason")) for r in receipts)),
    "contentStates": dict(Counter(str((r.get("diagnostics") or {}).get("contentState")) for r in receipts)),
    "httpStatuses": dict(Counter(str((r.get("diagnostics") or {}).get("httpStatus")) for r in receipts)),
    "requestIdsAvailable": sum(bool((r.get("diagnostics") or {}).get("requestId")) for r in receipts),
    "receiptAccounting": account(receipts),
    "campaignBefore": {"requests": len(old_rows), "settledMicroUsd": sum(row[2] for row in old_rows.values() if row[3])},
    "campaignAfter": {"requests": len(budget), "settledMicroUsd": sum(c for _, _, c, s in budget if s),
                      "uncertainReservations": sum(not s for _, _, _, s in budget),
                      "remainingMicroUsd": 5_000_000 - sum(c if s else r for _, r, c, s in budget)},
    "priorBudgetRowsUnchanged": True,
    "proposals": sum(row["proposals"] for row in results),
    "activations": sum(row["activations"] for row in results),
    "taskNativeWorkerCalls": sum(row["nativeCalls"] for row in results),
    "allSessionsCleaned": bool(results) and all(row["sessionCleaned"] for row in results),
    "allCachesCleaned": bool(results) and all(row["cacheCleaned"] for row in results),
    "allToolRegistriesCleaned": bool(results) and all(row["toolRegistryCleaned"] for row in results),
    "allObservedRawLedgersPreserved": all(row["rawLedgerPreserved"] for row in results if row["pagesRead"] > 0),
    "sourceSha256": before["sourceSha256"],
    "measurementInputSha256": {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [*source.glob("*.json"), *(p for p, _ in receipt_inputs)]},
    "validation": {},
    "historicalFinishReasonsStillUnknown": True,
    "unexecutedRuns": [f"{family}/1/{mode}" for family in ("long_json", "small_mixed")
                       for mode in ("original", "free", "conditional")
                       if not any(r["family"] == family and r["mode"] == mode for r in results)],
    "rawPageReads": sum(row["pagesRead"] for row in results),
    "nativePreflightCompletedBeforePaidCalls": bool(receipts),
    "validationScope": "15 targeted offline shared tests and shared:ktlintCheck; live test may stop on the baseline guard",
}
summary["sourceSha256"]["scripts/report-harness-diagnostic-pilot.py"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
target = root / "docs/reports/harness-diagnostic-pilot-data-2026-09-07" / source.name
target.mkdir(parents=True, exist_ok=True)
for task in ("jvmTest", "harnessLiveTest"):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (root / "shared/build/test-results" / task).glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
        shutil.copy2(path, target / f"{task}-{path.name}")
    summary["validation"][f"shared:{task}"] = totals
for path in source.glob("*.json"):
    shutil.copy2(path, target / path.name)
for name in ("harness-diagnostic-pilot-validation.log", "harness-diagnostic-pilot-lint.log", "harness-diagnostic-pilot-live.log"):
    shutil.copy2(root / "build/reports" / name, target / name)
for name, value in (("summary", summary), ("results", results), ("requests", receipts)):
    (target / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if not k.endswith("Sha256")}, indent=2))
