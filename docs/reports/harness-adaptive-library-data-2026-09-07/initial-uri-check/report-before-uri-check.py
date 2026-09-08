"""Freeze/reconcile the local adaptive Kotlin iteration; never reads provider credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
work = root / 'build/reports/harness-adaptation'
archive = root / 'docs/reports/harness-adaptive-library-data-2026-09-07'
parser = argparse.ArgumentParser()
parser.add_argument('--freeze', action='store_true')
args = parser.parse_args()
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
write = lambda p, value: p.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
with sqlite3.connect(f'file:{(root / "build/reports/harness-iteration/live/campaign-budget.sqlite").as_posix()}?mode=ro', uri=True) as db:
    budget = [list(row) for row in db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id')]
previous = read(root / 'build/reports/harness-runtime/frozen-final.json')
assert budget == previous['budgetRows'], 'Previous shared budget changed'
bases = {'workerSha256': root / 'harness-kotlin/build/install/harness-kotlin', 'javaRuntimeSha256': root / 'harness-kotlin/build/runtime/image'}
if args.freeze:
    work.mkdir(parents=True, exist_ok=True)
    assert not (work / 'frozen.json').exists(), 'Do not overwrite frozen evidence'
    sources = [root / 'shared/build.gradle.kts', Path(__file__).resolve()]
    for area, names in {
        'commonMain': ['HarnessAdaptation', 'HarnessTools', 'HarnessMutation', 'HarnessLabApprovalGate', 'ToolContractRegistry', 'ActionExecutor', 'AIAgent'],
        'jvmMain': ['AdaptiveSessionHarness', 'HarnessAdaptationLibrary', 'HarnessAdaptationEvaluator', 'AgentBootstrap', 'SessionHarness', 'HarnessStore', 'HarnessKotlinRunner', 'KotlinRuntimePreparation'],
        'jvmTest': ['AdaptiveSessionHarnessTest', 'AdaptiveHarnessAgentLoopTest', 'HarnessAdaptationNativeTest'],
    }.items():
        sources += [root / f'shared/src/{area}/kotlin/dev/promethe/core/{name}.kt' for name in names]
    frozen = {'sourceSha256': {p.relative_to(root).as_posix(): sha(p) for p in sources}, 'budgetRows': budget}
    for key, base in bases.items():
        frozen[key] = {p.relative_to(base).as_posix(): sha(p) for p in sorted(base.rglob('*')) if p.is_file()}
    frozen['helperSha256'] = sha(root / 'sandbox-native/target/release/promethe-sandbox.exe')
    write(work / 'frozen.json', frozen)
    print('Frozen source, worker, JRE, helper and unchanged shared budget')
else:
    frozen = read(work / 'frozen.json')
    assert frozen['budgetRows'] == budget
    for key, base in {'sourceSha256': root, **bases}.items():
        assert all(sha(base / name) == value for name, value in frozen[key].items()), f'{key} changed'
    assert frozen['helperSha256'] == sha(root / 'sandbox-native/target/release/promethe-sandbox.exe')
    summary = read(work / 'native-final/summary.json')
    assert summary['tasks'] == 2 and summary['correctTransformedObservations'] == 12 and summary['modelCalls'] == 0
    assert all(summary[k] for k in ['freshRevisionAcrossSessions', 'runtimeCleaned', 'rawHashesVerified', 'invalidationPreservesOriginal'])
    assert [d['choice'] for d in read(work / 'native-final/decisions.json')] == ['CREATE', 'REUSE']
    counts = {}
    for module, task in [('api', 'jvmTest'), ('shared', 'jvmTest'), ('gateway', 'test'), ('evals', 'test'), ('composeApp', 'desktopTest')]:
        results = [ET.parse(p).getroot() for p in (root / module / 'build/test-results' / task).glob('TEST-*.xml')]
        assert results, module
        counts[module] = {k: sum(int(r.get(k, 0)) for r in results) for k in ('tests', 'failures', 'errors', 'skipped')}
    assert all(r['failures'] == r['errors'] == r['skipped'] == 0 for r in counts.values())
    native_xml = root / 'shared/build/test-results/harnessKotlinNativeTest/TEST-dev.promethe.core.HarnessAdaptationNativeTest.xml'
    native = ET.parse(native_xml).getroot()
    assert native.get('tests') == '1' and native.get('failures') == native.get('errors') == '0'
    summary.update(jvmTests=sum(r['tests'] for r in counts.values()), nativeTests=1, budgetRowsUnchanged=True,
                   campaignRequests=len(budget), consumedMicroUsd=sum(c if s else r for _,r,c,s in budget),
                   limitation='Windows/Java21 native replay with pinned prior source; scripted agent integration, no autonomous model decision or provider cost/latency measurement')
    archive.mkdir(parents=True, exist_ok=True)
    for name in ('summary.json', 'decisions.json', 'library.json', 'compatibility.txt', 'preflight.json'):
        shutil.copy2(work / 'native-final' / name, archive / name)
    initial = archive / 'initial-preflight'
    initial.mkdir(exist_ok=True)
    for name in ('initial-validation.log', 'initial-native-test.xml', 'frozen-initial.json', 'HarnessAdaptationNativeTest-virtual-clock.kt', 'report-at-initial-freeze.py'):
        shutil.copy2(work / name, initial / name)
    write(initial / 'interpretation.json', dict(nativeTasksExecuted=0, cause='runTest virtual clock expired external-process preflight; corrected to runBlocking. Direct real-time helper self-test passed. No sandbox configuration changes.'))
    write(archive / 'summary.json', summary)
    write(archive / 'validation-counts.json', counts)
    shutil.copy2(work / 'frozen.json', archive / 'frozen.json')
    shutil.copy2(native_xml, archive / 'native-test.xml')
    for name in ('AdaptiveSessionHarnessTest', 'AdaptiveHarnessAgentLoopTest'):
        shutil.copy2(root / 'shared/build/test-results/jvmTest' / f'TEST-dev.promethe.core.{name}.xml', archive / f'{name}.xml')
    for name in ('harness-adaptation-focused.log', 'harness-adaptation-validation.log', 'harness-adaptation-docs.log'):
        path = root / 'build/reports' / name
        if path.exists(): shutil.copy2(path, archive / name)
    write(archive / 'artifact-sha256.json', {p.relative_to(archive).as_posix(): sha(p) for p in sorted(archive.rglob('*')) if p.is_file() and p.name != 'artifact-sha256.json'})
    print(json.dumps(summary, indent=2))
