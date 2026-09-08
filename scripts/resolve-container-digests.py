"""Resolve public OCI manifest digests anonymously. No Docker credentials used."""
import json
import urllib.parse
import urllib.request

ACCEPT = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


def resolve(registry, repository, tag):
    if registry == "ghcr.io":
        auth = "https://ghcr.io/token?" + urllib.parse.urlencode({
            "service": "ghcr.io", "scope": f"repository:{repository}:pull"})
    else:
        auth = "https://auth.docker.io/token?" + urllib.parse.urlencode({
            "service": "registry.docker.io", "scope": f"repository:{repository}:pull"})
    with urllib.request.urlopen(auth, timeout=30) as response:
        token = json.load(response)["token"]
    request = urllib.request.Request(f"https://{registry}/v2/{repository}/manifests/{tag}",
                                     headers={"Authorization": f"Bearer {token}", "Accept": ACCEPT})
    with urllib.request.urlopen(request, timeout=30) as response:
        digest = response.headers["Docker-Content-Digest"]
        manifest = json.load(response)
    if not digest or not digest.startswith("sha256:"):
        raise ValueError("Registry did not provide a SHA-256 manifest digest")
    return {"image": f"{registry}/{repository}:{tag}", "digest": digest,
            "platforms": [m.get("platform") for m in manifest.get("manifests", [])]}


if __name__ == "__main__":
    images = [
        ("registry-1.docker.io", "library/eclipse-temurin", "21-jdk-alpine"),
        ("registry-1.docker.io", "library/eclipse-temurin", "21-jre-alpine"),
        ("registry-1.docker.io", "library/caddy", "2.10.0-alpine"),
        ("ghcr.io", "berriai/litellm", "v1.100.0"),
    ]
    print(json.dumps([resolve(*image) for image in images], indent=2))
