"""Capture SkillOps validation evidence without contacting a model or reading credentials."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
archive = root / 'docs/reports/skill-evaluation-data-2026-09-08'
archive.mkdir(parents=True, exist_ok=True)


def write(name, value):
    (archive / name).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


counts = {}
for module, task in [('api', 'jvmTest'), ('shared', 'jvmTest'), ('gateway', 'test'), ('evals', 'test'), ('composeApp', 'desktopTest')]:
    results = [ET.parse(p).getroot() for p in (root / module / 'build/test-results' / task).glob('TEST-*.xml')]
    assert results, module
    counts[module] = {key: sum(int(r.get(key, 0)) for r in results) for key in ('tests', 'failures', 'errors', 'skipped')}
assert all(c['failures'] == c['errors'] == c['skipped'] == 0 for c in counts.values()), counts
log = root / 'build/reports/skill-evaluations-validation.log'
assert 'BUILD SUCCESSFUL' in log.read_text(encoding='utf-8-sig')
shutil.copy2(log, archive / 'validation.log')
for module, task, package, name in [
    ('shared', 'jvmTest', 'core', 'SkillEvaluationLifecycleTest'),
    ('shared', 'jvmTest', 'core', 'SkillReuseAgentLoopTest'),
    ('shared', 'jvmTest', 'core', 'SkillLoaderCacheTest'),
    ('shared', 'jvmTest', 'core', 'SkillWriterTest'),
    ('gateway', 'test', 'gateway', 'SkillRoutesTest'),
]:
    source = root / f'{module}/build/test-results/{task}/TEST-dev.promethe.{package}.{name}.xml'
    shutil.copy2(source, archive / f'{name}.xml')
budget_path = root / 'build/reports/harness-iteration/live/campaign-budget.sqlite'
with sqlite3.connect(f'file:{budget_path.as_posix()}?mode=ro', uri=True) as db:
    budget = [list(row) for row in db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id')]
previous = json.loads((root / 'build/reports/harness-runtime/frozen-final.json').read_text(encoding='utf-8-sig'))
assert budget == previous['budgetRows'], 'Historical model budget changed'
sources = [Path(__file__).resolve()]
for prefix, names in {
    'api/src/commonMain/kotlin/dev/promethe/api': ['SkillModels', 'SkillEvaluationModels'],
    'shared/src/commonMain/kotlin/dev/promethe/core': ['SkillLoader', 'SkillWriter', 'SkillGovernanceStore', 'SkillEvaluationService', 'evolution/GepaEvolver'],
    'shared/src/jvmTest/kotlin/dev/promethe/core': ['SkillEvaluationLifecycleTest', 'SkillReuseAgentLoopTest', 'SkillLoaderTest'],
    'gateway/src/jvmMain/kotlin/dev/promethe/gateway': ['SkillRoutes', 'OmnichannelGateway'],
    'gateway/src/test/kotlin/dev/promethe/gateway': ['SkillRoutesTest'],
    'composeApp/src/commonMain/kotlin/dev/promethe/app': ['screens/SkillsScreen', 'screens/SkillValidationPanel', 'screens/viewmodel/SkillsViewModel', 'network/PrometheClient'],
}.items():
    sources.extend(root / prefix / f'{name}.kt' for name in names)
sources.extend(root / f'composeApp/src/commonMain/composeResources/{locale}/strings.xml' for locale in ('values', 'values-fr'))
write('source-sha256.json', {p.relative_to(root).as_posix(): sha(p) for p in sources})
write('validation-counts.json', counts)
summary = {
    'scope': 'Version-bound text skill evaluations, owner-reviewed promotion, durable snapshots and quarantined restoration',
    'jvmTests': sum(c['tests'] for c in counts.values()),
    'addedTests': 13,
    'failures': 0,
    'kotlinStyleChecks': 'passed: api, shared, composeApp, harness-kotlin',
    'paidModelCallsThisIteration': 0,
    'historicalBudgetRowsUnchanged': True,
    'historicalCampaignRequests': len(budget),
    'historicalConsumedMicroUsd': sum(c if s else r for _, r, c, s in budget),
    'historicalUnsettledReservations': sum(1 for _, _, _, settled in budget if not settled),
    'reuseScenario': {
        'oracleInputs': ['invoice 2', 'invoice 3'],
        'agentTaskInputs': ['invoice 5', 'invoice 6'],
        'assertedAgentAnswers': ['10', '12'],
        'freshAgentAndLoaderPerTask': True,
        'provider': 'deterministic fixture, no remote model',
        'evidence': 'SkillReuseAgentLoopTest.xml',
    },
    'limitations': [
        'Exact text-output cases only; does not evaluate tool effects, auxiliary scripts or transitive dependency contents.',
        'Real AIAgent context assembly with scripted provider proves reuse wiring, not model quality improvement.',
        'Unmodified legacy and bundled skills retain compatibility without new evaluation evidence.',
        'Local snapshots and owner review are unsigned; not a transaction history or a boundary against the filesystem owner.',
        'Writers and evaluations are serialized within one process; no cross-process writer stress or locking claim.',
        'Desktop code compiled and desktop suite passed; new dialog not manually exercised on a running desktop.',
        'User-triggered application evaluations use the configured model and may incur provider costs.',
    ],
}
docs_log = root / 'build/reports/skill-evaluations-docs.log'
if docs_log.exists():
    assert 'Result: 36/36 checks passed' in docs_log.read_text(encoding='utf-8-sig')
    shutil.copy2(docs_log, archive / 'documentation.log')
    summary['documentationChecks'] = '36/36'
write('summary.json', summary)
write('artifact-sha256.json', {p.relative_to(archive).as_posix(): sha(p) for p in sorted(archive.rglob('*')) if p.is_file() and p.name != 'artifact-sha256.json'})
print(json.dumps(summary, indent=2))
