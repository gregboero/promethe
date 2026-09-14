"""Export non-secret, reviewable evidence from the local harness experiments."""
import json
import re
from pathlib import Path
import sqlite3
import statistics
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
reports = root / "build/reports/harness-iteration"
target = root / "docs/reports/harness-iteration-data-2026-09-06"
target.mkdir(parents=True, exist_ok=True)
summary = {}
# Preserve successful terminal evidence alongside machine-readable scenarios.
for name in ["final-validation.log", "native-tests.log", "live.log", "live-agent.log", "docs.log"]:
    source = reports / name
    if source.exists():
        contents = source.read_text(encoding="utf-8", errors="replace")
        (target / name).write_text(contents, encoding="utf-8")
        if name == "native-tests.log":
            matches = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed", contents)
            summary["nativeWindowsTests"] = {"passed": sum(int(p) for p, _ in matches), "failed": sum(int(f) for _, f in matches)}
        if name == "docs.log":
            match = re.search(r"Result: (\d+)/(\d+) checks passed", contents)
            if match:
                summary["documentationChecks"] = {"passed": int(match[1]), "total": int(match[2])}
for name in ["cordis-source.json", "hermes-learning-probe.json"]:
    source = reports / name
    if source.exists():
        data = json.loads(source.read_text(encoding="utf-8"))
        (target / name).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
cordis_path = reports / "cordis-tests.json"
if cordis_path.exists():
    cordis = json.loads(cordis_path.read_text(encoding="utf-8"))
    data = {key: cordis[key] for key in ["numTotalTests", "numPassedTests", "numFailedTests", "numPendingTests"]}
    data["tests"] = [{"name": case["fullName"], "status": case["status"]} for suite in cordis["testResults"] for case in suite["assertionResults"]]
    (target / "cordis-tests.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
    summary["cordis"] = {key: value for key, value in data.items() if key != "tests"}
for module, task in [("api", "jvmTest"), ("shared", "jvmTest"), ("gateway", "test"), ("evals", "test"), ("composeApp", "desktopTest"), ("shared", "harnessLiveTest")]:
    xmls = list((root / module / "build/test-results" / task).glob("TEST-*.xml"))
    if xmls:
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        for xml in xmls:
            suite = ET.parse(xml).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
        summary[f"{module}:{task}"] = totals
live = reports / "live"
for name in ["native-preflight.json", "processor-preflight.json", "paired-results.json", "agent-loop-result.json"]:
    source = live / name
    if source.exists():
        data = json.loads(source.read_text(encoding="utf-8"))
        (target / name).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        if name == "agent-loop-result.json":
            transcript = live / f"{data['session']}-transcript.json"
            if transcript.exists():
                (target / "agent-loop-transcript.json").write_text(transcript.read_text(encoding="utf-8"), encoding="utf-8")
requests = [json.loads(path.read_text(encoding="utf-8")) for path in live.glob("request-*.json")]
(target / "model-requests.json").write_text(json.dumps(requests, indent=2), encoding="utf-8")
budget = live / "campaign-budget.sqlite"
if budget.exists():
    with sqlite3.connect(f"file:{budget.as_posix()}?mode=ro", uri=True) as db:
        rows = db.execute("SELECT reserved, charged, settled FROM harness_budget").fetchall()
        summary["campaignBudget"] = {
            "capMicroUsd": 5_000_000, "requestsReserved": len(rows),
            "settledRequests": sum(row[2] for row in rows),
            "settledCostUpperBoundMicroUsd": sum(row[1] for row in rows if row[2]),
            "uncertainReservationMicroUsd": sum(row[0] for row in rows if not row[2]),
            "committedMicroUsd": sum(row[1] if row[2] else row[0] for row in rows),
        }
        revisions = []
        for body, validated in db.execute("SELECT body,validated FROM harness_revisions"):
            revision = json.loads(body)
            revision["validated"] = bool(validated)
            processed = db.execute("SELECT MIN(created) FROM harness_events WHERE revision=? AND event LIKE 'processed:%'", (revision["id"],)).fetchone()[0]
            revision["validationActivationProcessingMillis"] = None if processed is None else processed - revision["createdAt"]
            revisions.append(revision)
        (target / "model-revisions.json").write_text(json.dumps(revisions, indent=2, ensure_ascii=False), encoding="utf-8")
        paired_path = live / "paired-results.json"
        if paired_path.exists():
            paired = json.loads(paired_path.read_text(encoding="utf-8"))
            by_revision = {revision["id"]: revision for revision in revisions}
            metrics = []
            for family in ["json", "csv", "log"]:
                cases = [case for case in paired if case["family"] == family]
                groups = {}
                for variant in ["baseline", "mutation", "generation"]:
                    group = []
                    for case in cases:
                        session = by_revision[case["revision"]]["sessionId"]
                        matches = [request for request in requests if request["label"] == f"{session}-{variant}"]
                        if not matches:
                            matches = [request for request in requests if request["label"] == f"{family}-{case['repeat']}-{variant}"]
                        if len(matches) != 1:
                            raise ValueError("Ambiguous or missing request cohort; select the matching evidence")
                        group.extend(matches)
                    groups[variant] = group
                metrics.append({
                    "family": family, "cases": len(cases),
                    "baselineCorrect": sum(case["baselineCorrect"] for case in cases),
                    "mutationCorrect": sum(case["mutationCorrect"] for case in cases),
                    "medianRawBytes": statistics.median(case["rawBytes"] for case in cases),
                    "medianPresentedBytes": statistics.median(case["presentedBytes"] for case in cases),
                    "medianBaselineInputTokens": statistics.median(r["inputTokens"] for r in groups["baseline"]),
                    "medianMutationInputTokens": statistics.median(r["inputTokens"] for r in groups["mutation"]),
                    "baselineCostUpperBoundMicroUsd": sum(r["accountedUpperBoundMicroUsd"] for r in groups["baseline"]),
                    "mutationAndGenerationCostUpperBoundMicroUsd": sum(r["accountedUpperBoundMicroUsd"] for variant in ["mutation", "generation"] for r in groups[variant]),
                    "medianBaselineApiMillis": statistics.median(r["elapsedMillis"] for r in groups["baseline"]),
                    "medianMutationApiMillis": statistics.median(r["elapsedMillis"] for r in groups["mutation"]),
                    "medianGenerationApiMillis": statistics.median(r["elapsedMillis"] for r in groups["generation"]),
                    "medianValidationActivationProcessingMillis": statistics.median(by_revision[case["revision"]]["validationActivationProcessingMillis"] for case in cases),
                })
            (target / "comparison-metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
(target / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary, indent=2))
