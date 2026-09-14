"""Exercise pinned Hermes skill persistence with synthetic content, no model calls.

Run with the experiment venv. HERMES_HOME must point to a disposable directory.
This proves tool lifecycle and cross-process reuse, not autonomous learning.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
source = root / "build/experiments/hermes/hermes-agent-2026.8.31"
home = root / "build/experiments/hermes-learning-profile"
home.mkdir(parents=True, exist_ok=True)
env = {k: v for k, v in os.environ.items() if k.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "SYSTEMDRIVE"}}
env.update(HERMES_HOME=str(home), HOME=str(home), USERPROFILE=str(home), PYTHONIOENCODING="utf-8", DO_NOT_TRACK="1")
python = source / ".venv/Scripts/python.exe"
code = r'''
import json
from tools.skill_manager_tool import skill_manage
from tools.skills_tool import skill_view, skills_list
content = "---\nname: promethe-observation-probe\ndescription: Extract answers from synthetic JSON observations.\n---\n\n# Observation probe\n\nRead the answer field and ignore noise. Revision one.\n"
created = json.loads(skill_manage(action="create", name="promethe-observation-probe", content=content, session_id="probe"))
assert created.get("success"), created
assert "Revision one" in skill_view("promethe-observation-probe", preprocess=False)
patched = json.loads(skill_manage(action="patch", name="promethe-observation-probe", old_string="Revision one.", new_string="Revision two. Preserve the raw observation.", session_id="probe"))
assert patched.get("success"), patched
assert "Revision two" in skill_view("promethe-observation-probe", preprocess=False)
assert "promethe-observation-probe" in skills_list()
print(json.dumps({"created": True, "patched": True, "listed": True}))
'''
# Idempotent rerun uses a fresh name-specific probe home; do not delete existing skills.
if (home / "skills/promethe-observation-probe").exists():
    import uuid
    home = home / str(uuid.uuid4())
    home.mkdir()
    env.update(HERMES_HOME=str(home), HOME=str(home), USERPROFILE=str(home))
report = {"commit": "29112bef099274229cadff79cdff7bf7b99c4b77", "version": "v2026.8.31", "paidCalls": 0, "autonomousLearningTested": False}
try:
    created = subprocess.run([str(python), "-c", code], cwd=source, env=env, capture_output=True, text=True, encoding="utf-8", timeout=60)
    report.update(createPatchExit=created.returncode, createPatchOutput=created.stdout[-4000:], createPatchError=created.stderr[-4000:])
    reload_code = 'from tools.skills_tool import skill_view; assert "Revision two" in skill_view("promethe-observation-probe", preprocess=False); print("cross-process-reuse-ok")'
    reloaded = subprocess.run([str(python), "-c", reload_code], cwd=source, env=env, capture_output=True, text=True, encoding="utf-8", timeout=60)
    report.update(reloadExit=reloaded.returncode, reloadOutput=reloaded.stdout[-1000:], reloadError=reloaded.stderr[-2000:])
    report["passed"] = created.returncode == 0 and reloaded.returncode == 0
except Exception as error:
    report.update(passed=False, error=str(error))
output = root / "build/reports/harness-iteration/hermes-learning-probe.json"
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
sys.exit(0 if report["passed"] else 1)
