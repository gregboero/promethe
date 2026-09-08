"""Fetch pinned public source archives into build/experiments (no credentials)."""
from pathlib import Path
import io
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1] / "build/experiments"
root.mkdir(parents=True, exist_ok=True)
for name, url in {
    "koog-skills": "https://repo.maven.apache.org/maven2/ai/koog/skills-jvm/1.2.0-beta/skills-jvm-1.2.0-beta-sources.jar",
    "koog-deepseek": "https://repo.maven.apache.org/maven2/ai/koog/prompt-executor-deepseek-client-jvm/1.2.0-beta/prompt-executor-deepseek-client-jvm-1.2.0-beta-sources.jar",
    "koog-http": "https://repo.maven.apache.org/maven2/ai/koog/http-client-core-jvm/1.2.0/http-client-core-jvm-1.2.0-sources.jar",
    "hermes": "https://codeload.github.com/NousResearch/hermes-agent/zip/refs/tags/v2026.8.31",
}.items():
    target = root / name
    if target.exists():
        print(name, "already fetched")
        continue
    raw = urllib.request.urlopen(url, timeout=60).read()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for item in archive.infolist():
            if not (target / item.filename).resolve().is_relative_to(target.resolve()):
                raise ValueError("Archive path escapes extraction directory")
        archive.extractall(target)
    import hashlib
    print(name, hashlib.sha256(raw).hexdigest())
