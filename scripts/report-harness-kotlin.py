"""Archive the Kotlin LAB evidence; read the existing shared campaign budget only."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import statistics
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = root / 'build/reports/harness-kotlin'
target = root / 'docs/reports/harness-kotlin-data-2026-09-07'
target.mkdir(parents=True, exist_ok=True)
for name in ['native-results.json', 'language-benchmark.json', 'agent-loop-result.json']:
    shutil.copyfile(source / name, target / name)
loop = json.loads((source / 'agent-loop-result.json').read_text(encoding='utf-8'))
requests = [json.loads(path.read_text(encoding='utf-8')) for path in source.glob('request-*.json')]
for request in requests:
    # Synthetic responses only; credentials and HTTP headers are never recorded.
    shutil.copyfile(source / ('request-' + request['id'] + '.json'), target / ('request-' + request['id'] + '.json'))
for path in source.glob('agent-live-*-transcript.json'):
    shutil.copyfile(path, target / path.name)
budget_path = root / 'build/reports/harness-iteration/live/campaign-budget.sqlite'
with sqlite3.connect(f'file:{budget_path.as_posix()}?mode=ro', uri=True) as db:
    budget = db.execute('SELECT reserved,charged,settled FROM harness_budget').fetchall()
    revisions = [json.loads(body) | {'validated': bool(validated)} for body, validated in db.execute('SELECT body,validated FROM harness_revisions WHERE session=?', (loop['session'],))]
(target / 'model-revisions.json').write_text(json.dumps(revisions, indent=2, ensure_ascii=False), encoding='utf-8')
benchmark = json.loads((source / 'language-benchmark.json').read_text(encoding='utf-8'))
summary = {
    'languages': {language: {'batches': len(rows), 'observationsPerBatch': 5, 'correctBatches': sum(row['correct'] for row in rows), 'medianWallMillis': statistics.median(row['wallMillis'] for row in rows)} for language in ['JAVASCRIPT', 'KOTLIN'] for rows in [[row for row in benchmark if row['language'] == language]]},
    'iterationRequests': len(requests),
    'iterationCostUpperBoundMicroUsd': sum(request['accountedUpperBoundMicroUsd'] for request in requests),
    'campaignCapMicroUsd': 5_000_000,
    'campaignRequests': len(budget),
    'campaignSettledCostUpperBoundMicroUsd': sum(charged for _, charged, settled in budget if settled),
    'campaignUncertainReservationMicroUsd': sum(reserved for reserved, _, settled in budget if not settled),
    'tests': {},
}
for module, task in [('api','jvmTest'),('shared','jvmTest'),('gateway','test'),('evals','test'),('composeApp','desktopTest'),('shared','harnessKotlinNativeTest'),('shared','harnessLiveTest')]:
    suites = [ET.parse(path).getroot() for path in (root/module/'build/test-results'/task).glob('TEST-*.xml')]
    summary['tests'][f'{module}:{task}'] = {key: sum(int(suite.get(key, 0)) for suite in suites) for key in ['tests','failures','errors','skipped']}
files = list((root/'harness-kotlin').glob('native/*')) + list((root/'harness-kotlin/src').rglob('*.kt'))
files += [root/'harness-kotlin/build.gradle.kts', root/'harness-kotlin/gradle.lockfile']
files += list((root/'shared/src/jvmMain/kotlin/dev/promethe/core').glob('Harness*Runner.kt'))
summary['sourceSha256'] = {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
(target/'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary, indent=2))
