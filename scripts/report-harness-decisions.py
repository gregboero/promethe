"""Export a named decision cohort without rewriting the historical September 6 evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import statistics
import xml.etree.ElementTree as ET
import runpy

summarize_receipts = runpy.run_path(str(Path(__file__).with_name("harness-receipt-accounting.py")))["summarize"]

parser = argparse.ArgumentParser()
parser.add_argument("batch", help="Exact decision-UUID directory under the existing live report directory")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
live = root / "build/reports/harness-iteration/live"
source = (live / args.batch).resolve()
exposure = source.name.startswith("kotlin-exposure-")
kotlin = exposure or source.name.startswith("kotlin-decision-")
if source.parent != live.resolve() or not (kotlin or source.name.startswith("decision-")):
    raise ValueError("Expected one local decision cohort directory")
target = root / ("docs/reports/harness-exposure-data-2026-09-07" if exposure else "docs/reports/harness-kotlin-decision-data-2026-09-07" if kotlin else "docs/reports/harness-decision-data-2026-09-07") / source.name
target.mkdir(parents=True, exist_ok=True)
results = json.loads((source / "results.json").read_text(encoding="utf-8"))
protocol = json.loads((source / "protocol.json").read_text(encoding="utf-8"))
requests = [json.loads(path.read_text(encoding="utf-8")) for path in live.glob("request-*.json")]
cohort_requests = [request for request in requests if request["label"].startswith(source.name + "-")]
for result in results:
    receipts = [request for request in cohort_requests if request["label"].startswith(result["session"] + "-call-")]
    result.update(summarize_receipts(receipts))
    result["modelApiMillis"] = sum(request["elapsedMillis"] for request in receipts)
    if exposure:
        first = next((receipt for receipt in receipts if receipt["label"] == result["session"] + "-call-1"), None)
        result["firstCallInputTokens"] = first["inputTokens"] if first is not None else None
        result["firstVisibleResponseEmpty"] = first is not None and (first.get("response") == "" or (first.get("diagnostics") or {}).get("contentState") in ("empty", "blank", "null"))
    result["modelAbstained"] = result["mode"] in ["free", "conditional"] and result["completed"] and result.get("distinctPagesRead", result["pagesRead"]) == protocol["pagesPerTask"] and result["proposals"] == 0 and (not exposure or result["exposedAfterReads"] is not None)

metrics = []
for family in sorted({row["family"] for row in results}):
    for mode in (["original", "free", "conditional"] if exposure else ["original", "fixed", "free"]):
        rows = [row for row in results if row["family"] == family and row["mode"] == mode]
        if not rows:
            continue
        metrics.append({
            "family": family, "mode": mode, "runs": len(rows),
            "correct": sum(row["correct"] for row in rows),
            "completed": sum(row["completed"] for row in rows),
            "totalCostUpperBoundMicroUsd": sum(row["costUpperBoundMicroUsd"] for row in rows),
            "uncertainReservationMicroUsd": sum(row["uncertainReservationMicroUsd"] for row in rows),
            "unknownUsageCalls": sum(row["unknownUsageCalls"] for row in rows),
            "medianTotalMillis": statistics.median(row["totalMillis"] for row in rows),
            "medianNativeMillis": statistics.median(row["nativeMillis"] for row in rows),
            "medianInputTokens": statistics.median(row["inputTokens"] for row in rows) if all(row["usageComplete"] for row in rows) else None,
            "totalModelCalls": sum(row["attemptedCalls"] for row in rows),
            "medianModelApiMillis": statistics.median(row["modelApiMillis"] for row in rows),
            "proposals": sum(row["proposals"] for row in rows),
            "activations": sum(row["activations"] for row in rows),
            "processed": sum(row["processed"] for row in rows),
            "runtimeBypasses": sum(row["bypassedSmall"] + row["bypassedNoSaving"] for row in rows),
            "modelAbstentions": sum(row["modelAbstained"] for row in rows),
            **({
                "exposedRuns": sum(row["exposedAfterReads"] is not None for row in rows),
                "harnessPromptCalls": sum(row["harnessPromptCalls"] for row in rows),
                "medianFirstCallInputTokens": statistics.median(row["firstCallInputTokens"] for row in rows) if all(row["firstCallInputTokens"] is not None for row in rows) else None,
                "correctRunMedianCostMicroUsd": statistics.median(row["costUpperBoundMicroUsd"] for row in rows if row["correct"]) if any(row["correct"] for row in rows) else None,
                "correctRunMedianTotalMillis": statistics.median(row["totalMillis"] for row in rows if row["correct"]) if any(row["correct"] for row in rows) else None,
            } if exposure else {}),
            **({
                "cacheHits": sum(row["cacheHits"] for row in rows),
                "compilations": sum(row["compilations"] for row in rows),
                "workerProcesses": sum(row["workerProcesses"] for row in rows),
            } if kotlin else {}),
        })
with sqlite3.connect(f"file:{(live / 'campaign-budget.sqlite').as_posix()}?mode=ro", uri=True) as db:
    budget = db.execute("SELECT reserved,charged,settled FROM harness_budget").fetchall()
    revisions = [json.loads(body) | {"validated": bool(validated)} for body, validated in db.execute("SELECT body,validated FROM harness_revisions WHERE session LIKE ?", (source.name + "%",))]
summary = {
    "batch": source.name, "plannedRuns": protocol["plannedRuns"], "recordedRuns": len(results),
    "correctRuns": sum(row["correct"] for row in results),
    "completedRuns": sum(row["completed"] for row in results),
    "allRawLedgersPreserved": all(row["rawLedgerPreserved"] for row in results),
    "allSessionsCleaned": all(row["sessionCleaned"] for row in results),
    "cohortSettledRequests": summarize_receipts(cohort_requests)["settledCalls"],
    "cohortCostUpperBoundMicroUsd": summarize_receipts(cohort_requests)["costUpperBoundMicroUsd"],
    "cohortReceiptAccounting": summarize_receipts(cohort_requests),
    "campaignCapMicroUsd": 5_000_000,
    "campaignReservedRequests": len(budget),
    "campaignSettledCostUpperBoundMicroUsd": sum(charged for _, charged, settled in budget if settled),
    "campaignUncertainReservationMicroUsd": sum(reserved for reserved, _, settled in budget if not settled),
}
if kotlin:
    summary["validationScope"] = {
        "executedThisIteration": ["shared:ktlintCheck", "shared:jvmTest", "shared:harnessLiveTest", "native identity and fixed-source fixtures preflight"] + (["api:jvmTest", "gateway:test", "evals:test", "composeApp:desktopTest"] if exposure else []),
        "historicalXmlSnapshotsOnly": [] if exposure else ["api:jvmTest", "gateway:test", "evals:test", "composeApp:desktopTest"],
    }
    summary["sourceSha256"] = {
        name: hashlib.sha256((root / name).read_bytes()).hexdigest()
        for name in [
            "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessDecisionLiveTest.kt",
            "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessLiveCampaignTest.kt",
            "shared/src/jvmMain/kotlin/dev/promethe/core/HarnessKotlinRunner.kt",
            "shared/src/jvmMain/kotlin/dev/promethe/core/KotlinCompilationCache.kt",
            "shared/src/jvmMain/kotlin/dev/promethe/core/SessionHarness.kt",
            "scripts/report-harness-decisions.py",
            "scripts/harness-receipt-accounting.py",
            "shared/src/jvmTest/kotlin/dev/promethe/core/CampaignResponseDiagnostics.kt",
            "shared/src/jvmMain/kotlin/dev/promethe/core/HarnessTaskCompletion.kt",
        ]
    }
    if exposure:
        for name in ["shared/src/commonMain/kotlin/dev/promethe/core/AIAgent.kt", "shared/src/jvmMain/kotlin/dev/promethe/core/HarnessExposurePolicy.kt"]:
            summary["sourceSha256"][name] = hashlib.sha256((root / name).read_bytes()).hexdigest()
        summary["allToolRegistriesCleaned"] = all(row["toolRegistryCleaned"] for row in results)
        observed = [row for row in results if row["pagesRead"] > 0]
        summary["allObservedRawLedgersPreserved"] = bool(observed) and all(row["rawLedgerPreserved"] for row in observed)
        summary["zeroObservationRuns"] = sum(row["pagesRead"] == 0 for row in results)
    summary["allCachesCleaned"] = all(row["cacheCleaned"] for row in results)
    summary["pairedComparisons"] = []
    for row in results:
        if row["mode"] == "original":
            continue
        baseline = next((other for other in results if other["family"] == row["family"] and other["repeat"] == row["repeat"] and other["mode"] == "original"), None)
        if baseline is None:
            continue
        summary["pairedComparisons"].append({
            "family": row["family"], "repeat": row["repeat"], "mode": row["mode"],
            "bothCorrect": row["correct"] and baseline["correct"],
            "costDeltaMicroUsd": row["costUpperBoundMicroUsd"] - baseline["costUpperBoundMicroUsd"] if not (row["uncertainCalls"] or baseline["uncertainCalls"]) else None,
            "latencyDeltaMillis": row["totalMillis"] - baseline["totalMillis"],
            "inputTokenDelta": row["inputTokens"] - baseline["inputTokens"] if row["usageComplete"] and baseline["usageComplete"] else None,
            "modelProposed": row["mode"] in ["free", "conditional"] and row["proposals"] > 0,
        })
    if exposure:
        summary["conditionalVersusAlwaysAvailable"] = []
        for row in results:
            if row["mode"] != "conditional":
                continue
            baseline = next((other for other in results if other["family"] == row["family"] and other["repeat"] == row["repeat"] and other["mode"] == "free"), None)
            if baseline is not None:
                summary["conditionalVersusAlwaysAvailable"].append({
                    "family": row["family"], "repeat": row["repeat"],
                    "bothCorrect": row["correct"] and baseline["correct"],
                    "costDeltaMicroUsd": row["costUpperBoundMicroUsd"] - baseline["costUpperBoundMicroUsd"] if not (row["uncertainCalls"] or baseline["uncertainCalls"]) else None,
                    "latencyDeltaMillis": row["totalMillis"] - baseline["totalMillis"],
                    "inputTokenDelta": row["inputTokens"] - baseline["inputTokens"] if row["usageComplete"] and baseline["usageComplete"] else None,
                })
for module, task in [("api", "jvmTest"), ("shared", "jvmTest"), ("gateway", "test"), ("evals", "test"), ("composeApp", "desktopTest"), ("shared", "harnessLiveTest")]:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for xml in (root / module / "build/test-results" / task).glob("TEST-*.xml"):
        suite = ET.parse(xml).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    summary[f"{module}:{task}"] = totals
for name, value in {"summary": summary, "protocol": protocol, "results": results, "metrics": metrics, "requests": cohort_requests, "revisions": revisions}.items():
    (target / f"{name}.json").write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")
for transcript in source.glob("*-transcript.json"):
    shutil.copyfile(transcript, target / transcript.name)
for corpus in source.glob("*-corpus.json"):
    shutil.copyfile(corpus, target / corpus.name)
if exposure:
    for name in ["initial-results.json", "initial-HarnessDecisionLiveTest.kt", "continuation.json"]:
        if (source / name).exists():
            shutil.copyfile(source / name, target / name)
prefix = "exposure" if exposure else "kotlin-decision" if kotlin else "decision"
for name in [f"{prefix}-live.log", f"{prefix}-final-validation.log", f"{prefix}-docs.log"] + (["exposure-initial-validation.log", "exposure-initial-live.log", "exposure-initial-live.xml", "exposure-resume-validation.log"] if exposure else []):
    log = live.parent / name
    if log.exists():
        shutil.copyfile(log, target / name)
print(json.dumps(summary, indent=2))
