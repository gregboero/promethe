"""Archive local quota validation and verify the historical model budget read-only."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
archive = root / 'docs/reports/resource-aggregate-quotas-data-2026-09-07'
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

log = root / 'build/reports/resource-quotas-validation.log'
assert 'BUILD SUCCESSFUL' in log.read_text(encoding='utf-8-sig')
shutil.copy2(log, archive / 'validation.log')
quota_tests = 0
for name in ('PersistentResourceQuotaBankTest', 'ResourceQuotaIntegrationTest'):
    path = root / f'shared/build/test-results/jvmTest/TEST-dev.promethe.core.{name}.xml'
    quota_tests += int(ET.parse(path).getroot().get('tests'))
    shutil.copy2(path, archive / f'{name}.xml')

budget_path = root / 'build/reports/harness-iteration/live/campaign-budget.sqlite'
with sqlite3.connect(f'file:{budget_path.as_posix()}?mode=ro', uri=True) as db:
    budget = [list(row) for row in db.execute('SELECT id,reserved,charged,settled FROM harness_budget ORDER BY id')]
previous = json.loads((root / 'build/reports/harness-runtime/frozen-final.json').read_text(encoding='utf-8-sig'))
assert budget == previous['budgetRows'], 'Historical model budget changed'

sources = [Path(__file__).resolve()]
for area, names in {
    'commonMain': ['ResourceQuotas', 'ResourceGovernor', 'KoogLlmAdapter', 'ActionExecutor', 'tools/builtin/IntrospectionTools'],
    'jvmMain': ['PersistentResourceQuotaBank', 'PersistentResourceGovernorRegistry', 'AgentBootstrap'],
    'jvmTest': ['PersistentResourceQuotaBankTest', 'ResourceQuotaIntegrationTest'],
}.items():
    sources.extend(root / f'shared/src/{area}/kotlin/dev/promethe/core/{name}.kt' for name in names)
write('source-sha256.json', {p.relative_to(root).as_posix(): sha(p) for p in sources})
write('validation-counts.json', counts)
summary = {
    'scope': 'Local profile aggregate start quotas: owner, actual provider and exact tool name',
    'jvmTests': sum(c['tests'] for c in counts.values()),
    'newQuotaTests': quota_tests,
    'failures': 0,
    'kotlinStyleChecks': 'passed: api, shared, composeApp, harness-kotlin',
    'paidModelCallsThisIteration': 0,
    'historicalBudgetRowsUnchanged': True,
    'historicalCampaignRequests': len(budget),
    'historicalConsumedMicroUsd': sum(c if s else r for _, r, c, s in budget),
    'historicalUnsettledReservations': sum(1 for _, _, _, settled in budget if not settled),
    'validationEnvironment': 'Windows, Corretto Java 21; real Koog client using localhost HTTP fixtures',
    'limitations': [
        'Counts admitted starts, not aggregate tokens, USD or every SDK-internal HTTP retry.',
        'Owner is a local profile, not an authenticated multi-user identity.',
        'Concurrency test uses four bank instances within one JVM; no separate-process stress claim.',
        'Policies are loaded at bootstrap; changing policy identity or window creates another counter.',
        'Aggregate reservation is retained if later run persistence or execution fails; no automatic refund.',
    ],
}
docs_log = root / 'build/reports/resource-quotas-docs.log'
if docs_log.exists():
    docs_text = docs_log.read_text(encoding='utf-8-sig')
    assert 'Result: 36/36 checks passed' in docs_text
    shutil.copy2(docs_log, archive / 'documentation.log')
    summary['documentationChecks'] = '36/36'
write('summary.json', summary)
write('artifact-sha256.json', {p.relative_to(archive).as_posix(): sha(p) for p in sorted(archive.rglob('*')) if p.is_file() and p.name != 'artifact-sha256.json'})
print(json.dumps(summary, indent=2))
