"""Reconcile the runtime preparation comparison and retain its initial diagnostic profile."""
import hashlib
import argparse
import json
from pathlib import Path
import shutil
import sqlite3
from statistics import median
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--final', action='store_true')
args = parser.parse_args()
work = root / 'build/reports/harness-runtime'
cohort = 'comparison-final' if args.final else 'comparison'
frozen_name = 'frozen-final.json' if args.final else 'frozen.json'
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
write = lambda p, v: p.write_text(json.dumps(v, indent=2) + '\n', encoding='utf-8')
frozen = read(work / frozen_name)
for hashes, base in [(frozen['sourceSha256'], root), (frozen['workerSha256'], root / 'harness-kotlin/build/install/harness-kotlin'), (frozen['javaRuntimeSha256'], root / 'harness-kotlin/build/runtime/image')]:
    assert all(sha(base / name) == value for name, value in hashes.items())
with sqlite3.connect(f'file:{(root / "build/reports/harness-iteration/live/campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    budget = db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id').fetchall()
assert [list(r) for r in budget] == frozen['budgetRows']
rows = read(work / cohort / 'results.json')
assert [(r['repetition'], r['mode']) for r in rows] == [(0, 'baseline'), (0, 'optimized'), (1, 'optimized'), (1, 'baseline'), (2, 'baseline'), (2, 'optimized')]
assert all(r['failure'] is None and r['correctPages'] == 8 and r['cacheCleaned'] and r['runtimeCleaned'] and r['sessionCleaned'] and r['rawArtifactsPreserved'] for r in rows)
assert all(r['compilations'] == 1 and r['cacheHits'] == 9 and r['workerProcesses'] == 11 for r in rows)
assert all(len(r['timings']) == 10 and all(t['successful'] for t in r['timings']) for r in rows)
isolation = read(work / cohort / 'isolation.json')
assert all(isolation.values())
modes = {}
for mode in ('baseline', 'optimized'):
    selected = [r for r in rows if r['mode'] == mode]
    modes[mode] = {k: median(r[k] for r in selected) for k in ('wallMillis', 'setupMillis', 'compilationMillis', 'evaluationMillis', 'stagingMillis', 'processMillis')}
    modes[mode]['medianPageMillis'] = median(v for r in selected for v in r['pageMillis'])
    for key in ('statusMillis', 'cleanupMillis', 'totalMillis'):
        modes[mode]['medianRunner' + key[0].upper() + key[1:]] = median(sum(t[key] for t in r['timings']) for r in selected)
    for key in ('sandboxMillis', 'childMillis', 'workerUptimeMillis'):
        modes[mode]['medianWarmEvaluation' + key[0].upper() + key[1:]] = median(w[key] for r in selected for t in r['timings'][1:] for w in t['workers'])
pairs = []
for repetition in range(3):
    pair = {r['mode']: r for r in rows if r['repetition'] == repetition}
    pairs.append(dict(repetition=repetition, savedMillis=pair['baseline']['wallMillis'] - pair['optimized']['wallMillis'], reductionPercent=100 * (1 - pair['optimized']['wallMillis'] / pair['baseline']['wallMillis'])))
counts = {}
for module, task in [('api', 'jvmTest'), ('shared', 'jvmTest'), ('gateway', 'test'), ('evals', 'test'), ('composeApp', 'desktopTest')]:
    roots = [ET.parse(p).getroot() for p in (root / module / 'build/test-results' / task).glob('TEST-*.xml')]
    counts[module] = {k: sum(int(r.get(k, 0)) for r in roots) for k in ('tests', 'failures', 'errors', 'skipped')}
assert all(r['failures'] == r['errors'] == r['skipped'] == 0 for r in counts.values())
native_xml = root / 'shared/build/test-results/harnessKotlinNativeTest/TEST-dev.promethe.core.HarnessKotlinRuntimeNativeTest.xml'
native = ET.parse(native_xml).getroot()
assert native.get('tests') == '1' and native.get('failures') == native.get('errors') == '0'
summary = dict(successfulRuns=6, correctPages=48, medianByMode=modes, pairedDifferences=pairs,
               wallReductionPercent=100 * (1 - modes['optimized']['wallMillis'] / modes['baseline']['wallMillis']),
               modelCalls=0, addedCostMicroUsd=0, budgetRowsUnchanged=True, sourcesUnchanged=True,
               jvmTests=sum(r['tests'] for r in counts.values()), nativeTests=1, isolation=isolation,
               campaignRequests=len(budget), consumedMicroUsd=sum(c if s else r for _,r,c,s in budget), remainingMicroUsd=5_000_000-sum(c if s else r for _,r,c,s in budget),
               limitations='Windows/Java 21, three alternating pairs and one synthetic extraction task; no provider or full application latency measurement; per-call cleanup excludes endSession cleanup, which is included in wallMillis')
target = root / 'docs/reports/harness-kotlin-runtime-data-2026-09-07'
if args.final: target = target / 'final'
target.mkdir(parents=True, exist_ok=True)
for name in ('protocol.json', 'results.json', 'isolation.json'):
    shutil.copy2(work / cohort / name, target / name)
shutil.copytree(work / 'profile', target / 'initial-profile', dirs_exist_ok=True, ignore=shutil.ignore_patterns('*.sqlite', 'artifacts'))
write(target / 'initial-profile' / 'interpretation.json', dict(actualRuns=1, actualMode='baseline', note='Initial diagnostic run only. Its inherited protocol repetition/order fields describe the later comparison template, not the actual profile count. No optimized profile was run.'))
shutil.copy2(work / frozen_name, target / 'frozen.json')
for name in ('harness-runtime-final-validation.log' if args.final else 'harness-runtime-validation.log', 'harness-runtime-focused.log', 'harness-runtime-profile.log'):
    shutil.copy2(root / 'build/reports' / name, target / name)
shutil.copy2(native_xml, target / 'native-test.xml')
for name in ('KotlinRuntimePreparationTest', 'HarnessKotlinRunnerTest'):
    shutil.copy2(root / 'shared/build/test-results/jvmTest' / f'TEST-dev.promethe.core.{name}.xml', target / f'{name}.xml')
write(target / 'summary.json', summary)
write(target / 'validation-counts.json', counts)
write(target / 'artifact-sha256.json', {p.relative_to(target).as_posix(): sha(p) for p in target.rglob('*') if p.is_file() and p.name != 'artifact-sha256.json'})
print(json.dumps(summary, indent=2))
