"""Export the cache iteration without overwriting previous Kotlin evidence or resetting its budget."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import statistics
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = root / 'build/reports/harness-kotlin-cache'
target = root / 'docs/reports/harness-kotlin-cache-data-2026-09-07'
target.mkdir(parents=True, exist_ok=True)
rows = json.loads((source/'cache-benchmark.json').read_text(encoding='utf-8'))
assert len(rows) == 27 and all(row['correct'] for row in rows)
metrics = {}
for mode in ['javascript', 'kotlin_uncached', 'kotlin_cached']:
    group = [row for row in rows if row['mode'] == mode]
    sequences = [sum(row['wallMillis'] for row in group if row['repetition'] == repetition) for repetition in range(3)]
    metrics[mode] = {
        'batches': len(group), 'correctObservations': sum(row['observations'] for row in group),
        'medianThreeBatchSequenceMillis': statistics.median(sequences),
        'medianFirstBatchMillis': statistics.median(row['wallMillis'] for row in group if row['step'] == 0),
        'medianFollowingBatchMillis': statistics.median(row['wallMillis'] for row in group if row['step'] > 0),
        'maxBatchMillis': max(row['wallMillis'] for row in group),
        'sequenceMillis': sequences,
        'cacheHits': sum(row.get('cacheHit', False) for row in group),
        'workerProcesses': sum(row.get('workerProcesses', 1) for row in group),
    }
requests = [json.loads(path.read_text(encoding='utf-8')) for path in source.glob('request-*.json')]
loop = json.loads((source/'agent-loop-result.json').read_text(encoding='utf-8'))
budget_path = root/'build/reports/harness-iteration/live/campaign-budget.sqlite'
with sqlite3.connect(f'file:{budget_path.as_posix()}?mode=ro', uri=True) as db:
    budget = db.execute('SELECT reserved,charged,settled FROM harness_budget').fetchall()
    revisions = [json.loads(body) | {'validated': bool(validated)} for body, validated in db.execute('SELECT body,validated FROM harness_revisions WHERE session=?', (loop['session'],))]
summary = {
    'benchmark': metrics,
    'sequenceReductionVersusUncachedPercent': 100 * (1 - metrics['kotlin_cached']['medianThreeBatchSequenceMillis'] / metrics['kotlin_uncached']['medianThreeBatchSequenceMillis']),
    'iterationRequests': len(requests),
    'iterationCostUpperBoundMicroUsd': sum(request['accountedUpperBoundMicroUsd'] for request in requests),
    'campaignRequests': len(budget), 'campaignCapMicroUsd': 5_000_000,
    'campaignSettledCostUpperBoundMicroUsd': sum(charged for _, charged, settled in budget if settled),
    'campaignUncertainReservationMicroUsd': sum(reserved for reserved, _, settled in budget if not settled),
    'tests': {},
}
for module, task in [('api','jvmTest'),('shared','jvmTest'),('gateway','test'),('evals','test'),('composeApp','desktopTest'),('shared','harnessKotlinNativeTest'),('shared','harnessLiveTest')]:
    suites = [ET.parse(path).getroot() for path in (root/module/'build/test-results'/task).glob('TEST-*.xml')]
    summary['tests'][f'{module}:{task}'] = {key: sum(int(suite.get(key, 0)) for suite in suites) for key in ['tests','failures','errors','skipped']}
files = list((root/'harness-kotlin/src').rglob('*.kt')) + list((root/'harness-kotlin/native').glob('*'))
files += [root/'shared/src/jvmMain/kotlin/dev/promethe/core'/name for name in ['HarnessKotlinRunner.kt','KotlinCompilationCache.kt','SessionHarness.kt','HarnessNodeRunner.kt']]
files += [root/'shared/src/jvmMain/kotlin/dev/promethe/core/sandbox/NativeSandboxManager.kt']
summary['sourceSha256'] = {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
for name in ['cache-benchmark.json','cache-lifecycle.json','native-results.json','agent-loop-result.json','diagnostic-two-second-limit.xml','diagnostic-transport-timeout.json','diagnostic-partial-benchmark.json']:
    shutil.copyfile(source/name, target/name)
for pattern in ['request-*.json','agent-live-*-transcript.json']:
    for path in source.glob(pattern): shutil.copyfile(path, target/path.name)
for name in ['kotlin-cache-native.log','kotlin-cache-live.log','kotlin-cache-final-validation.log','kotlin-cache-docs.log']:
    path = root/'build/reports/harness-iteration'/name
    if path.exists(): shutil.copyfile(path, target/name)
(target/'model-revisions.json').write_text(json.dumps(revisions, indent=2, ensure_ascii=False), encoding='utf-8')
(target/'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary, indent=2))
