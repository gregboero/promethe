"""Contract checks for Compose; no daemon, image pull, or container is used."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DOCKER = shutil.which("docker") or r"C:\Program Files\Docker\Docker\resources\bin\docker.exe"
COMPOSE_PLUGIN = Path(DOCKER).parent.parent / "cli-plugins" / "docker-compose.exe"


class ContainerConfigurationTest(unittest.TestCase):
    def compose(self, overlay=None, variables=None, success=True):
        with tempfile.TemporaryDirectory() as config:
            env = {k: v for k, v in os.environ.items() if k.upper() in
                   {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE", "HOME"}}
            env.update(variables or {})
            env["DOCKER_CONFIG"] = config
            compose = [str(COMPOSE_PLUGIN)] if COMPOSE_PLUGIN.is_file() else [DOCKER, "compose"]
            command = compose + ["--env-file", os.devnull, "-f", "docker-compose.yml"]
            if overlay:
                command += ["-f", overlay]
            result = subprocess.run(command + ["config", "--format", "json"], cwd=ROOT, env=env,
                                    text=True, capture_output=True, timeout=30)
            if not success:
                self.assertNotEqual(result.returncode, 0)
                return None
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)

    def test_base_publishes_only_loopback_and_disables_effectful_backends(self):
        service = self.compose()["services"]["promethe-core"]
        self.assertTrue(all(port["host_ip"] == "127.0.0.1" for port in service["ports"]))
        self.assertEqual(service["environment"]["SANDBOX_NETWORK_MODE"], "OFF")
        for key in ("CODE_EXECUTION_ENABLED", "PROCESS_TOOL_ENABLED", "DOCKER_TOOL_ENABLED", "REMOTE_ACCESS_ENABLED"):
            self.assertEqual(service["environment"][key], "false")

    def test_litellm_requires_key_and_model_then_uses_loopback_and_pinned_image(self):
        self.compose("compose.litellm.yaml", success=False)
        self.compose("compose.litellm.yaml", {"LITELLM_MODEL": "test"}, success=False)
        service = self.compose("compose.litellm.yaml", {
            "LITELLM_MODEL": "test", "LITELLM_MASTER_KEY": "sk-disposable-config-test-only",
        })["services"]["litellm"]
        self.assertRegex(service["image"], r"@sha256:[0-9a-f]{64}$")
        self.assertTrue(all(p["host_ip"] == "127.0.0.1" for p in service["ports"]))
        self.assertTrue(service["volumes"][0]["read_only"])

    def test_memory_overlays_require_explicit_endpoints_without_starting_unverified_services(self):
        for provider, prefix, url_key in (("honcho", "HONCHO", "HONCHO_URL"),
                                          ("tencent", "TENCENT_MEMORY", "TENCENT_MEMORY_URL")):
            with self.subTest(provider=provider):
                overlay = f"compose.{provider}.yaml"
                self.compose(overlay, success=False)
                result = self.compose(overlay, {url_key: "http://host.docker.internal:9999",
                                                f"{prefix}_API_KEY": "disposable-config-test-only"})
                self.assertEqual(set(result["services"]), {"promethe-core"})

    def test_docker_build_includes_each_gradle_module_and_pins_base_images(self):
        dockerfile = (ROOT / "Dockerfile").read_text()
        modules = re.findall(r'include\(":(\w+)"\)', (ROOT / "settings.gradle.kts").read_text())
        for module in modules:
            self.assertIn(f"COPY {module}/ {module}/", dockerfile)
        for base in re.findall(r"^FROM (\S+)", dockerfile, re.MULTILINE):
            self.assertRegex(base, r"@sha256:[0-9a-f]{64}$")
        self.assertNotIn("|| true", dockerfile)


if __name__ == "__main__":
    unittest.main()
