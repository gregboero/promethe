"""Reconcile the directed Kotlin control and longer free-choice comparison without rewriting earlier cohorts."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument('batch')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
live = root / 'build/reports/harness-iteration/live'
source = (live / args.batch).resolve()
assert source.parent == live.resolve() and source.name.startswith('kotlin-directed-')
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
protocol, results, preflight = [read(source / f'{name}.json') for name in ('protocol', 'results', 'preflight')]
before = read(root / 'build/reports/harness-directed/before.json')
assert all(hashlib.sha256((root / p).read_bytes()).hexdigest() == h for p, h in before['sourceSha256'].items())
receipts = [read(p) for p in live.glob('request-*.json')]
receipts = [r for r in receipts if r['label'].startswith(source.name + '-')]
with sqlite3.connect(f'file:{(live / "campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    rows = db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id').fetchall()
old = {r[0]: tuple(r) for r in before['budgetRows']}
added = [r for r in rows if r[0] not in old]
assert len(rows) == len(old) + len(added) and all(old[r[0]] == r for r in rows if r[0] in old)
assert {r[0] for r in added} == {r['id'] for r in receipts}
assert len(receipts) <= 36 and sum(c if s else r for _, r, c, s in rows) <= 5_000_000
account = runpy.run_path(str(root / 'scripts/harness-receipt-accounting.py'))['summarize']
for result in results:
    calls = sorted([r for r in receipts if r['label'].startswith(result['session'] + '-call-')], key=lambda r: int(r['label'].rsplit('-call-', 1)[1]))
    result['accounting'] = account(calls)
    result['relayMillis'] = sum(r['elapsedMillis'] for r in calls)
    phases = {r['call']: r for r in result['callPhases']}
    main, review = [], []
    prior = 0
    for receipt in calls:
        phase = phases[int(receipt['label'].rsplit('-call-', 1)[1])]
        assert receipt['nativeHistoryPairsValid']
        assert receipt['model'] == 'gpt-5.6-terra' and receipt['reasoningEffort'] == 'none' and receipt['maxOutputTokens'] == 4096
        if phase['agentStep'] is not None:
            assert receipt['toolDefinitions'] == (1 if result['mode'] == 'baseline' else 7)
            assert receipt['toolResultInputs'] == prior
            prior += receipt['functionCalls']
            main.append(receipt)
        else:
            assert receipt['toolDefinitions'] == 0
            review.append(receipt)
    result['mainAccounting'], result['reviewAccounting'] = account(main), account(review)
summary = dict(batch=source.name, plannedRuns=3, recordedRuns=len(results), completeCohort=len(results)==3,
    successfulRuns=sum(r['correct'] and r['completed'] for r in results), accounting=account(receipts),
    proposals=sum(r['proposals'] for r in results), activations=sum(r['activations'] for r in results),
    processed=sum(r['processed'] for r in results), preflight=preflight,
    priorBudgetRowsUnchanged=True, sourceSha256=before['sourceSha256'],
    campaignAfter=dict(requests=len(rows), consumedMicroUsd=sum(c if s else r for _, r, c, s in rows),
        uncertainReservations=sum(not s for _, _, _, s in rows), remainingMicroUsd=5_000_000-sum(c if s else r for _, r, c, s in rows)))
target = root / 'docs/reports/harness-kotlin-directed-data-2026-09-07' / source.name
target.mkdir(parents=True, exist_ok=True)
for path in source.glob('*.json'): shutil.copy2(path, target / path.name)
for name in ('harness-directed-final-validation.log', 'harness-directed-live.log'):
    shutil.copy2(root / 'build/reports' / name, target / name)
shutil.copy2(root / 'shared/build/test-results/harnessLiveTest/TEST-dev.promethe.core.HarnessKotlinDirectedLiveTest.xml', target / 'live-test.xml')
with sqlite3.connect(f'file:{(live / "campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    candidates = []
    for result in results:
        for ident, body, validated in db.execute('SELECT id,body,validated FROM harness_revisions WHERE session=?', (result['session'],)):
            revision = json.loads(body)
            assert hashlib.sha256(revision['source'].encode('utf-8')).hexdigest() == revision['hash']
            candidates.append(dict(mode=result['mode'], revision=revision, validationRecorded=bool(validated)))
summary['directedSuccess'] = any(r['mode']=='directed' and r['completed'] and r['correct'] and r['activations']>0 and r['processed']==2 for r in results)
summary['pairedRuns'] = sum(r['mode']!='directed' for r in results)
summary['freeMutations'] = sum(r['activations'] for r in results if r['mode']=='free')
(target/'candidates.json').write_text(json.dumps(candidates,indent=2)+'\n',encoding='utf-8')
drafts = []
for result in results:
    for i, path in enumerate((source/result['session']).rglob('*.md')):
        name = f"{result['mode']}-draft-{i}.md"
        shutil.copy2(path,target/name)
        drafts.append(dict(mode=result['mode'],source=path.relative_to(source).as_posix(),archive=name))
(target/'drafts.json').write_text(json.dumps(drafts,indent=2)+'\n',encoding='utf-8')
for name, value in [('summary' , summary), ('results', results), ('requests', receipts)]:
    (target / f'{name}.json').write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k != 'sourceSha256'}, indent=2))
