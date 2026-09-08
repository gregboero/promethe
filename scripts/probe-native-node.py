"""Diagnose native process launch with fixed commands only; no generated code or model calls."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
workspace = Path(os.environ["PROMETHE_NATIVE_PROBE_WORKSPACE"]).resolve()
directory = workspace / ("harness-launch-probe-" + str(uuid.uuid4()))
directory.mkdir()
assert directory.resolve().parent == workspace
helper = root / "sandbox-native/target/release/promethe-sandbox.exe"
env = {k: v for k, v in os.environ.items() if k.upper() in {"SYSTEMROOT", "WINDIR", "COMSPEC", "PATH", "PATHEXT", "TEMP", "TMP", "PROGRAMDATA", "SYSTEMDRIVE"}}
results = []
try:
    node = directory / "node.exe"
    shutil.copyfile(os.environ["PROMETHE_HARNESS_NODE"], node)
    for mode in ["READ_ONLY", "WORKSPACE_WRITE"]:
        for executable, arguments in [(str(Path(os.environ["SystemRoot"]) / "System32/whoami.exe"), []), (str(node), ["--version"])]:
            request = {"protocolVersion": 1, "operation": "EXECUTE", "execution": {
                "protocolVersion": 1, "executionId": str(uuid.uuid4()), "sessionId": "native-launch-probe",
                "executable": executable, "arguments": arguments, "workingDirectory": str(workspace), "environment": {}, "sensitiveEnvironmentKeys": [], "interactive": False,
                "profile": {"mode": mode, "approvalPolicy": "NEVER", "networkMode": "OFF", "readableRoots": [str(workspace)], "writableRoots": [], "protectedPaths": [], "allowedDomains": [],
                            "limits": {"timeoutMillis": 2000, "maxOutputBytesPerStream": 65536, "memoryBytes": 268435456, "cpuLimit": 1.0, "processLimit": 1}}}}
            process = subprocess.run([str(helper)], input=json.dumps(request) + "\n", capture_output=True, text=True, encoding="utf-8", env=env, timeout=30)
            results.append({"mode": mode, "binary": Path(executable).name, "exit": process.returncode, "response": process.stdout[-6000:], "stderr": process.stderr[-1000:]})
finally:
    # Remove only the exact unique directory this script created under the named workspace.
    assert directory.resolve().parent == workspace and directory.name.startswith("harness-launch-probe-")
    shutil.rmtree(directory)
output = root / "build/reports/harness-iteration/native-launch-probe.json"
output.write_text(json.dumps(results, indent=2), encoding="utf-8")
print(json.dumps(results, indent=2))
