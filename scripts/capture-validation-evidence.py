"""Capture source fingerprints and existing test reports without source/secrets.

Run after validation. This records evidence; it does not execute tests and
never promotes a capability. stdout is a portable JSON manifest.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()


paths = git("ls-files", "--cached", "--others", "--exclude-standard", "--", ".").splitlines()
fingerprints = {}
for relative in sorted(set(paths)):
    path = ROOT / relative
    if not path.is_file() or path.is_symlink():
        continue
    if path.suffix not in {".kt", ".kts", ".toml", ".lock", ".rs", ".py", ".ps1", ".yaml", ".yml", ".xml"} and relative not in {
        "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "gradle/verification-metadata.xml", "Dockerfile",
        "web-tooling/package.json", "web-tooling/package-lock.json", ".dockerignore", ".gitignore", ".env.example",
    } and not relative.endswith("gradle.lockfile"):
        continue
    fingerprints[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
aggregate = hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()
tests = []
for module in ("api", "shared", "gateway", "evals", "composeApp", "androidApp"):
    for path in sorted((ROOT / module / "build/test-results").rglob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        tests.append({"module": module, "suite": suite.get("name"),
                      "tests": int(suite.get("tests", 0)), "failures": int(suite.get("failures", 0)),
                      "errors": int(suite.get("errors", 0)), "skipped": int(suite.get("skipped", 0)),
                      "timestamp": suite.get("timestamp"), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
print(json.dumps({"captured_at": datetime.now(timezone.utc).isoformat(), "head": git("rev-parse", "HEAD"),
                  "working_tree_dirty": bool(git("status", "--porcelain", "--", ".")),
                  "source_sha256": aggregate, "file_sha256": fingerprints,
                  "test_reports": tests,
                  "limitation": "Existing XML reports, not a claim of a fresh or complete run; compare timestamps and runner logs."}, indent=2))
