"""Read-only upstream version inventory; does not resolve or update dependencies.

Python 3.11+. Run from any directory. Writes JSON to stdout. Maven metadata
lists published versions, not compatibility or vulnerability guarantees.
"""
import concurrent.futures
import json
from pathlib import Path
import re
import tomllib
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
CATALOG = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
ROOT_BUILD = (ROOT / "build.gradle.kts").read_text()
ROOT_PLUGINS = dict(re.findall(r'(?:kotlin|id)\("([^"]+)"\)\s+version\s+"([^"]+)"', ROOT_BUILD))
for alias in re.findall(r'alias\(libs\.plugins\.([\w.]+)\)', ROOT_BUILD):
    plugin = CATALOG["plugins"][alias.replace(".", "-")]
    ROOT_PLUGINS[plugin["id"]] = CATALOG["versions"][plugin["version"]["ref"]]


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Promethe-research-audit/1.0"})
    with urllib.request.urlopen(request, timeout=25) as response:
        return response.read()


def numeric(version):
    return tuple(int(part) for part in version.split("."))


def inspect(item):
    result = dict(item)
    try:
        if item["ecosystem"] == "cargo":
            data = json.loads(fetch(item["source"]))
            result["latest_stable"] = data["crate"]["max_stable_version"]
        else:
            tree = ET.fromstring(fetch(item["source"]))
            versions = [node.text for node in tree.findall("./versioning/versions/version")]
            stable = [v for v in versions if re.fullmatch(r"\d+(?:\.\d+)+", v)]
            result["latest_stable"] = max(stable, key=numeric) if stable else None
            result["latest_published"] = tree.findtext("./versioning/latest")
            result["declared_published"] = item["declared"] in versions
            if item["module"].startswith("ai.koog:"):
                result["versions_1_2"] = [v for v in versions if v.startswith("1.2.")]
    except Exception as error:
        result["error"] = str(error)
    return result


def maven_item(name, module, version, origin, repository="https://repo.maven.apache.org/maven2"):
    group, artifact = module.split(":")
    return dict(name=name, ecosystem="maven", module=module, declared=version,
                origin=origin, source=f"{repository}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml")


items = []
for name, entry in CATALOG["libraries"].items():
    version = entry["version"]
    if isinstance(version, dict):
        version = CATALOG["versions"][version["ref"]]
    repo = "https://dl.google.com/dl/android/maven2" if entry["module"].startswith("androidx.") else "https://repo.maven.apache.org/maven2"
    items.append(maven_item(name, entry["module"], version, "gradle/libs.versions.toml", repo))
for path in [ROOT / "build.gradle.kts", *ROOT.glob("*/build.gradle.kts")]:
    for module, version in re.findall(r'"([\w.\-]+:[\w.\-]+):([\d][\w.\-]+)"', path.read_text()):
        repo = "https://dl.google.com/dl/android/maven2" if module.startswith("androidx.") else "https://repo.maven.apache.org/maven2"
        items.append(maven_item(module, module, version, path.relative_to(ROOT).as_posix(), repo))
for name, module, version, repo in [
    ("Kotlin Gradle plugin", "org.jetbrains.kotlin:kotlin-gradle-plugin", ROOT_PLUGINS.get("multiplatform", ROOT_PLUGINS.get("org.jetbrains.kotlin.multiplatform")), None),
    ("Compose Gradle plugin", "org.jetbrains.compose:compose-gradle-plugin", ROOT_PLUGINS["org.jetbrains.compose"], None),
    ("Android Gradle plugin", "com.android.tools.build:gradle", ROOT_PLUGINS["com.android.application"], "https://dl.google.com/dl/android/maven2"),
    ("ktlint Gradle plugin", "org.jlleitschuh.gradle.ktlint:org.jlleitschuh.gradle.ktlint.gradle.plugin", ROOT_PLUGINS["org.jlleitschuh.gradle.ktlint"], "https://plugins.gradle.org/m2"),
    ("ktlint engine", "com.pinterest.ktlint:ktlint-cli", CATALOG["versions"].get("ktlint-engine") or re.search(r'version\.set\("([^"]+)"\)', ROOT_BUILD).group(1), None),
]:
    items.append(maven_item(name, module, version, "build.gradle.kts", repo or "https://repo.maven.apache.org/maven2"))
for name, entry in CATALOG["plugins"].items():
    plugin_id = entry["id"]
    version = CATALOG["versions"][entry["version"]["ref"]]
    items.append(maven_item(f"plugin:{name}", f"{plugin_id}:{plugin_id}.gradle.plugin", version,
                            "gradle/libs.versions.toml", "https://dl.google.com/dl/android/maven2" if plugin_id.startswith("com.android.") else "https://plugins.gradle.org/m2"))
lock = tomllib.loads((ROOT / "sandbox-native/Cargo.lock").read_text())
for package in lock["package"]:
    if package.get("source", "").startswith("registry+"):
        name = package["name"]
        items.append(dict(name=name, ecosystem="cargo", module=name, declared=package["version"],
                          origin="sandbox-native/Cargo.lock", source=f"https://crates.io/api/v1/crates/{name}"))
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    results = list(pool.map(inspect, items))
print(json.dumps(dict(checked_at=datetime.now(timezone.utc).isoformat(),
                     scope="Declared Maven dependencies/plugins and locked Cargo packages; not a resolved JVM SBOM or CVE scan",
                     dependencies=results), indent=2, ensure_ascii=False))
