"""Freeze and reconcile the single CI diagnostic comparison; never resets the shared budget."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument('batch', nargs='?')
parser.add_argument('--freeze', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
live = root / 'build/reports/harness-iteration/live'
work = root / 'build/reports/harness-amortization'
work.mkdir(parents=True, exist_ok=True)
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
write = lambda p, obj: p.write_text(json.dumps(obj, indent=2) + '\n', encoding='utf-8')
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
with sqlite3.connect(f'file:{(live / "campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    rows = db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id').fetchall()
files = [
    'shared/src/jvmMain/kotlin/dev/promethe/core/HarnessExposurePolicy.kt',
    'shared/src/jvmMain/kotlin/dev/promethe/core/SessionHarness.kt',
    'shared/src/jvmMain/kotlin/dev/promethe/core/HarnessKotlinRunner.kt',
    'shared/src/jvmTest/kotlin/dev/promethe/core/HarnessKoogMutationFixture.kt',
    'shared/src/jvmTest/kotlin/dev/promethe/core/HarnessKotlinAmortizationLiveTest.kt',
    'shared/src/jvmTest/kotlin/dev/promethe/core/CampaignKoogRelay.kt',
    'shared/src/jvmTest/kotlin/dev/promethe/core/KoogMutationLoopTest.kt',
    'shared/build.gradle.kts',
    'scripts/report-kotlin-amortization.py',
]
if args.freeze:
    assert not args.batch and not (work / 'before.json').exists(), 'Do not overwrite a frozen baseline'
    assert len(rows) == 707 and sum(c if s else r for _, r, c, s in rows) == 4_242_730
    write(work / 'before.json', dict(budgetRows=rows, sourceSha256={p: sha(root / p) for p in files}))
    print('Frozen: 707 requests, 4242730 microUSD; 757270 microUSD remain')
    raise SystemExit
assert args.batch
source = (live / args.batch).resolve()
assert source.parent == live.resolve() and source.name.startswith('kotlin-amortization-')
before = read(work / 'before.json')
assert all(sha(root / p) == h for p, h in before['sourceSha256'].items())
old = {r[0]: tuple(r) for r in before['budgetRows']}
added = [r for r in rows if r[0] not in old]
assert len(rows) == len(old) + len(added) and all(old[r[0]] == r for r in rows if r[0] in old)
receipts = [read(p) for p in live.glob('request-*.json')]
receipts = [r for r in receipts if r['label'].startswith(source.name + '-')]
assert {r[0] for r in added} == {r['id'] for r in receipts}
assert len(receipts) <= 36 and sum(c if s else r for _, r, c, s in rows) <= 5_000_000
account = runpy.run_path(str(root / 'scripts/harness-receipt-accounting.py'))['summarize']
results = read(source / 'results.json')
for result in results:
    calls = sorted([r for r in receipts if r['label'].startswith(result['session'] + '-call-')], key=lambda r: int(r['label'].rsplit('-call-', 1)[1]))
    phases = {p['call']: p for p in result['callPhases']}
    main, review = [], []
    prior = 0
    for receipt in calls:
        phase = phases[int(receipt['label'].rsplit('-call-', 1)[1])]
        assert receipt['nativeHistoryPairsValid']
        assert receipt['model'] == 'gpt-5.6-terra' and receipt['reasoningEffort'] == 'none' and receipt['maxOutputTokens'] == 4096
        assert receipt['toolDefinitions'] == phase['formalToolCount']
        if phase['agentStep'] is not None:
            assert receipt['toolDefinitions'] == (7 if phase['harnessExposed'] else 1)
            assert receipt['toolResultInputs'] == prior
            prior += receipt['functionCalls']
            main.append(receipt)
        else:
            assert receipt['toolDefinitions'] == 0
            review.append(receipt)
    result.update(accounting=account(calls), mainAccounting=account(main), reviewAccounting=account(review),
                  relayMillis=sum(r['elapsedMillis'] for r in calls), requestBytes=sum(r['requestBytes'] for r in calls),
                  mainRequestBytes=sum(r['requestBytes'] for r in main), formalToolBytes=sum(r['formalToolBytes'] for r in main))
target = root / 'docs/reports/harness-kotlin-amortization-data-2026-09-07' / source.name
target.mkdir(parents=True, exist_ok=True)
for p in source.glob('*.json'): shutil.copy2(p, target / p.name)
for name in ('harness-amortization-validation.log', 'harness-amortization-live.log'):
    shutil.copy2(root / 'build/reports' / name, target / name)
shutil.copy2(root / 'shared/build/test-results/harnessLiveTest/TEST-dev.promethe.core.HarnessKotlinAmortizationLiveTest.xml', target / 'live-test.xml')
shutil.copy2(work / 'before.json', target / 'before.json')
shutil.copytree(work / 'offline', target / 'offline', dirs_exist_ok=True)
candidates, drafts = [], []
with sqlite3.connect(f'file:{(live / "campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    for result in results:
        for ident, body, validated in db.execute('SELECT id,body,validated FROM harness_revisions WHERE session=?', (result['session'],)):
            revision = json.loads(body)
            assert hashlib.sha256(revision['source'].encode()).hexdigest() == revision['hash']
            candidates.append(dict(mode=result['mode'], revision=revision, validationRecorded=bool(validated)))
        for i, p in enumerate((source / result['session']).rglob('*.md')):
            name = f"{result['mode']}-draft-{i}.md"
            shutil.copy2(p, target / name)
            drafts.append(dict(mode=result['mode'], archive=name))
prior_data = root / 'docs/reports/harness-kotlin-directed-data-2026-09-07'
old_free = read(prior_data / 'offline/free-4-requests.json')[0]
old_base = read(prior_data / 'offline/baseline-4-requests.json')[0]
size = lambda value: len(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode())
exposure = size(old_free) - size(old_base)
baseline = next((r for r in results if r['mode'] == 'baseline'), None)
reused = next((r for r in results if r['mode'] == 'reused'), None)
comparison = {}
if baseline and reused and all(r['correct'] and r['completed'] for r in (baseline, reused)):
    saving = baseline['accounting']['costUpperBoundMicroUsd'] - reused['accounting']['costUpperBoundMicroUsd']
    historical_directed_cost = 56_741
    comparison = dict(savingMicroUsd=saving, elapsedDeltaMillis=reused['totalMillis'] - baseline['totalMillis'],
                      historicalDirectedCostMicroUsd=historical_directed_cost,
                      illustrativeReusesToRecoverEntireDirectedRun=(historical_directed_cost + saving - 1) // saving if saving > 0 else None,
                      caveat='one fixed-order pair; historical directed run is not isolated source-generation cost; no general break-even claim')
summary = dict(batch=source.name, plannedRuns=3, recordedRuns=len(results),
               successfulRuns=sum(r['correct'] and r['completed'] for r in results), accounting=account(receipts),
               priorBudgetRowsUnchanged=True, sourcesUnchanged=True, observedExposureBytesPerCall=exposure,
               preflight=read(source / 'preflight.json'), comparison=comparison,
               campaignAfter=dict(requests=len(rows), consumedMicroUsd=sum(c if s else r for _, r, c, s in rows),
                   uncertainReservations=sum(not s for _, _, _, s in rows), remainingMicroUsd=5_000_000-sum(c if s else r for _, r, c, s in rows)))
for name, value in [('summary', summary), ('results', results), ('requests', receipts), ('candidates', candidates), ('drafts', drafts)]:
    write(target / f'{name}.json', value)
write(target / 'artifact-sha256.json', {p.relative_to(target).as_posix(): sha(p) for p in target.rglob('*') if p.is_file() and p.name != 'artifact-sha256.json'})
print(json.dumps(summary, indent=2))
