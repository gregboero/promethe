"""Archive offline replay and validation; never invoke a model or write the campaign DB."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
target = root / "docs/reports/harness-response-diagnostics-data-2026-09-07"
target.mkdir(parents=True, exist_ok=True)
replay_path = root / "build/reports/harness-response-diagnostics/historical-replay.json"
replay = json.loads(replay_path.read_text(encoding="utf-8"))
assert replay["counts"] == {"complete": 14, "empty_final_response": 3, "missing_page_reads": 1}
source = root / replay["source"]
inputs = [source / "results.json", source / "protocol.json", source / "requests.json", *sorted(source.glob("*-transcript.json"))]
hashes = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
budget_path = root / "build/reports/harness-iteration/live/campaign-budget.sqlite"
with sqlite3.connect(f"file:{budget_path.as_posix()}?mode=ro", uri=True) as db:
    budget = db.execute("SELECT reserved,charged,settled FROM harness_budget").fetchall()
budget_summary = dict(requests=len(budget), settledMicroUsd=sum(c for _, c, s in budget if s),
                      uncertainReservations=sum(not s for _, _, s in budget),
                      uncertainMicroUsd=sum(r for r, _, s in budget if not s))
assert budget_summary == dict(requests=591, settledMicroUsd=3603075, uncertainReservations=0, uncertainMicroUsd=0)
validation = {}
for module, task in [("api", "jvmTest"), ("shared", "jvmTest"), ("gateway", "test"), ("evals", "test"), ("composeApp", "desktopTest")]:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    xml_paths = sorted((root / module / "build/test-results" / task).glob("TEST-*.xml"))
    assert xml_paths, (module, task)
    for path in xml_paths:
        suite = ET.parse(path).getroot()
        for field in totals:
            totals[field] += int(suite.attrib.get(field, 0))
        if module == "shared" and any(name in path.name for name in ("CampaignResponseDiagnosticsTest", "HarnessTaskCompletionTest", "HarnessCompletionLoopTest", "HarnessHistoricalCompletionReplayTest")):
            shutil.copy2(path, target / path.name)
    assert totals["failures"] == totals["errors"] == 0, totals
    validation[f"{module}:{task}"] = totals
log = root / "build/reports/harness-response-final-validation.log"
assert "BUILD SUCCESSFUL" in log.read_text(encoding="utf-8")
docs_log = root / "build/reports/harness-response-docs.log"
assert "36/36 checks passed" in docs_log.read_text(encoding="utf-8")
python_log = root / "build/reports/harness-response-python-tests.log"
assert "Ran 2 tests" in python_log.read_text(encoding="utf-8") and "OK" in python_log.read_text(encoding="utf-8")
for path in (replay_path, log, docs_log, python_log):
    shutil.copy2(path, target / path.name)
source_paths = [
    "shared/src/commonMain/kotlin/dev/promethe/core/AIAgent.kt",
    "shared/src/commonMain/kotlin/dev/promethe/core/AgentCompletionValidator.kt",
    "shared/src/jvmMain/kotlin/dev/promethe/core/HarnessTaskCompletion.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/CampaignResponseDiagnostics.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessLiveCampaignTest.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessDecisionLiveTest.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/CampaignResponseDiagnosticsTest.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessTaskCompletionTest.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessCompletionLoopTest.kt",
    "shared/src/jvmTest/kotlin/dev/promethe/core/HarnessHistoricalCompletionReplayTest.kt",
    "scripts/harness-receipt-accounting.py",
    "scripts/test-harness-receipt-accounting.py",
    "scripts/report-harness-decisions.py",
    "scripts/report-harness-response-diagnostics.py",
]
summary = dict(modelCalls=0, additionalCostMicroUsd=0, campaignBudget=budget_summary,
               historicalReplay=replay["counts"], historicalFinishReasonsAvailable=False,
               validation=validation, receiptAccountingPythonTests=2, futureExposureProtocolVersion=6,
               historicalInputSha256=hashes,
               sourceSha256={name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in source_paths})
assert hashes == {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
(target / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if not k.endswith("Sha256")}, indent=2))
