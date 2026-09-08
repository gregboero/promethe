"""Fetch the audited Cordis revision; no user credentials or runtime configuration."""
import hashlib
import io
import json
import os
from pathlib import Path
import urllib.request
import zipfile

sha = "d347e703908d0406b7a7ef80e3a0e594d86b2215"
root = Path(__file__).resolve().parents[1]
target = root / "build/experiments/cordis"
url = f"https://codeload.github.com/deepseek-ai/deepseek-harness/zip/{sha}"
raw = urllib.request.urlopen(url, timeout=60).read()
with zipfile.ZipFile(io.BytesIO(raw)) as archive:
    for item in archive.infolist():
        if not (target / item.filename).resolve().is_relative_to(target.resolve()):
            raise ValueError("Archive traversal")
    # Windows extended paths avoid the 260-character limit of upstream fixtures.
    archive.extractall(("\\\\?\\" + str(target.resolve())) if os.name == "nt" else target)
report = {"commit": sha, "url": url, "archiveSha256": hashlib.sha256(raw).hexdigest()}
output = root / "build/reports/harness-iteration/cordis-source.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report))
