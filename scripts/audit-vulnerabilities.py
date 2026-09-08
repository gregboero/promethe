"""Query OSV for public Maven/Cargo/npm coordinates only; never sends source or secrets.

Run audit-resolved-dependencies.init.gradle.kts first. JSON on stdout.
Results indicate registry matches, not demonstrated exploitability.
"""
from datetime import datetime, timezone
import json
from pathlib import Path
import tomllib
import urllib.request
import urllib.parse
import re
import sys

root = Path(__file__).resolve().parents[1]
coordinates = set()
inventories = list((root / "build/reports/dependency-audit").glob("*-*Classpath.json"))
if len(inventories) != 6:
    raise SystemExit("Expected six resolved classpath inventories (including Android and evals); run the Gradle inventory first")
for path in inventories:
    for module in json.loads(path.read_text())["modules"]:
        coordinates.add(("Maven", module["group"] + ":" + module["artifact"], module["version"]))
for package in tomllib.loads((root / "sandbox-native/Cargo.lock").read_text())["package"]:
    if package.get("source", "").startswith("registry+"):
        coordinates.add(("crates.io", package["name"], package["version"]))
npm_count = 0
tooling_lock = root / "build/reports/dependency-audit/web-tooling-package-lock.json"
if not tooling_lock.is_file():
    raise SystemExit("Run Gradle auditWebTooling to include the actual Kotlin Web toolchain")
for yarn_lock in (root / "kotlin-js-store").rglob("yarn.lock"):
    # Yarn v1 tarball URLs identify the actual package even for npm aliases.
    for block in yarn_lock.read_text().split("\n\n"):
        version = re.search(r'^  version "([^"]+)"', block, re.MULTILINE)
        resolved = re.search(r'^  resolved "([^"]+)"', block, re.MULTILINE)
        if version and resolved:
            url = urllib.parse.urlparse(resolved[1])
            if url.hostname in {"registry.yarnpkg.com", "registry.npmjs.org"} and "/-/" in url.path:
                name = urllib.parse.unquote(url.path.split("/-/", 1)[0].lstrip("/"))
                coordinates.add(("npm", name, version[1]))
                npm_count += 1
for name, package in json.loads(tooling_lock.read_text())["packages"].items():
    resolved = package.get("resolved", "")
    if name and urllib.parse.urlparse(resolved).hostname == "registry.npmjs.org":
        coordinates.add(("npm", package.get("name") or name.rsplit("node_modules/", 1)[-1], package["version"]))
        npm_count += 1
queries = [{"package": {"ecosystem": eco, "name": name}, "version": version}
           for eco, name, version in sorted(coordinates)]
if "--dry-run" in sys.argv:
    print(json.dumps({"destination": "https://api.osv.dev/v1/querybatch", "queries": queries}, indent=2))
    sys.exit(0)
output = {"checked_at": datetime.now(timezone.utc).isoformat(), "source": "https://api.osv.dev/v1/querybatch",
          "scope": "Six resolved JVM/Desktop/Web/Android/evals classpaths, Cargo.lock, active Wasm Yarn lock and installed Web tooling npm lock; excludes non-registry Git/tarball dependencies, OS images, external services, disposable Python experiments and Gradle tooling plugins",
          "npm_lock_entries": npm_count,
          "queried": len(queries), "matches": [], "errors": []}
for start in range(0, len(queries), 100):
    batch = queries[start:start + 100]
    try:
        request = urllib.request.Request(output["source"], data=json.dumps({"queries": batch}).encode(),
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=45) as response:
            results = json.load(response)["results"]
        for query, result in zip(batch, results, strict=True):
            if result.get("vulns"):
                output["matches"].append({**query, **result})
            if result.get("next_page_token"):
                output["errors"].append({"query": query, "error": "Additional vulnerability results require pagination"})
    except Exception as error:
        output["errors"].append({"batch_start": start, "error": str(error)})
output["advisories"] = []
for advisory_id in sorted({v["id"] for match in output["matches"] for v in match["vulns"]}):
    try:
        with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{advisory_id}", timeout=25) as response:
            data = json.load(response)
        output["advisories"].append({key: data.get(key) for key in
                                      ("id", "summary", "aliases", "published", "modified", "withdrawn", "severity", "references", "affected")})
    except Exception as error:
        output["errors"].append({"advisory": advisory_id, "error": str(error)})
print(json.dumps(output, indent=2))
