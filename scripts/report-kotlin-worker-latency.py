"""Reconcile the local worker benchmark, including distribution hashes and unchanged paid ledger."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
from statistics import median
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
work = root / 'build/reports/harness-worker-latency'
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
write = lambda p, v: p.write_text(json.dumps(v, indent=2) + '\n', encoding='utf-8')
before, frozen = read(work / 'before.json'), read(work / 'frozen.json')
for hashes, base in [(before['baselineSha256'], work / 'baseline-dist'),
                     (frozen['optimizedSha256'], root / 'harness-kotlin/build/install/harness-kotlin'),
                     (frozen['javaRuntimeSha256'], root / 'harness-kotlin/build/runtime/image'),
                     (frozen['sourceSha256'], root)]:
    assert all(sha(base / name) == digest for name, digest in hashes.items())
with sqlite3.connect(f'file:{(root / "build/reports/harness-iteration/live/campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    budget = db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id').fetchall()
assert [list(r) for r in budget] == before['budgetRows']
rows = read(work / 'native/results.json')
assert [(r['repetition'], r['mode']) for r in rows] == [(0, 'baseline'), (0, 'optimized'), (1, 'optimized'), (1, 'baseline'), (2, 'baseline'), (2, 'optimized')]
assert all(r['failure'] is None and r['correctPages'] == 8 and r['cacheCleaned'] and r['sessionCleaned'] and r['rawArtifactsPreserved'] for r in rows)
assert all(r['compilations'] == 1 and r['cacheHits'] == 9 and r['workerProcesses'] == 11 for r in rows)
isolation = read(work / 'native/isolation.json')
assert all(isolation.values())
counts = {}
for module, task in [('api', 'jvmTest'), ('shared', 'jvmTest'), ('gateway', 'test'), ('evals', 'test'), ('composeApp', 'desktopTest')]:
    roots = [ET.parse(p).getroot() for p in (root / module / 'build/test-results' / task).glob('TEST-*.xml')]
    counts[module] = {key: sum(int(r.get(key, 0)) for r in roots) for key in ('tests', 'failures', 'errors', 'skipped')}
assert all(r['failures'] == r['errors'] == r['skipped'] == 0 for r in counts.values())
live_xml = root / 'shared/build/test-results/harnessKotlinNativeTest/TEST-dev.promethe.core.HarnessKotlinWorkerLatencyNativeTest.xml'
native = ET.parse(live_xml).getroot()
assert native.get('tests') == '1' and native.get('failures') == native.get('errors') == '0'
modes = {}
for mode in ('baseline', 'optimized'):
    selected = [r for r in rows if r['mode'] == mode]
    modes[mode] = {key: median(r[key] for r in selected) for key in ('wallMillis', 'setupMillis', 'compilationMillis', 'evaluationMillis', 'stagingMillis', 'processMillis')}
    modes[mode]['medianPageMillis'] = median(value for r in selected for value in r['pageMillis'])
pairs = []
for repetition in range(3):
    pair = {r['mode']: r for r in rows if r['repetition'] == repetition}
    pairs.append(dict(repetition=repetition, savedMillis=pair['baseline']['wallMillis'] - pair['optimized']['wallMillis'],
                      reductionPercent=100 * (1 - pair['optimized']['wallMillis'] / pair['baseline']['wallMillis'])))
summary = dict(modelCalls=0, addedCostMicroUsd=0, budgetRowsUnchanged=True, distributionsAndSourcesUnchanged=True,
               successfulRuns=6, correctPages=48, medianByMode=modes, pairedDifferences=pairs,
               medianWallReductionPercent=100 * (1 - modes['optimized']['wallMillis'] / modes['baseline']['wallMillis']),
               isolation=isolation, jvmTests=sum(r['tests'] for r in counts.values()), nativeTests=1,
               campaignRequests=len(budget), consumedMicroUsd=4_624_197, remainingMicroUsd=375_803,
               limitation='Three alternating local pairs on one JSON extraction script; combined optimization, no causal split; no model/app end-to-end or long CPU throughput measurement')
target = root / 'docs/reports/harness-kotlin-worker-latency-data-2026-09-07'
target.mkdir(parents=True, exist_ok=True)
for name in ('protocol.json', 'results.json', 'isolation.json'):
    shutil.copy2(work / 'native' / name, target / name)
for name in ('before.json', 'frozen.json', 'Main.kt', 'windows-launcher.c', 'HarnessKotlinRunner.kt'):
    shutil.copy2(work / name, target / ('baseline-' + name if name.endswith(('.kt', '.c')) else name))
for name in ('harness-worker-latency-validation.log', 'harness-worker-latency-build.log', 'harness-worker-latency-focused.log'):
    shutil.copy2(root / 'build/reports' / name, target / name)
shutil.copy2(live_xml, target / 'native-test.xml')
write(target / 'summary.json', summary)
write(target / 'validation-counts.json', counts)
write(target / 'artifact-sha256.json', {p.name: sha(p) for p in target.iterdir() if p.is_file() and p.name != 'artifact-sha256.json'})
print(json.dumps(summary, indent=2))
