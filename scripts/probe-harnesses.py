"""No-paid-call transport probes of installed, pinned upstream harnesses.

Only a fake OpenAI-compatible server on loopback receives model requests.
This is a harness smoke test, not a measure of reasoning quality.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
SENTINEL = "PROMETHE_LOCAL_PROBE_OK"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("harness", choices=["deepseek", "hermes"])
    parser.add_argument("--scenario", choices=["text", "tool"], default="text")
    parser.add_argument("--run-id", default="1", help="Safe label for independent repetitions")
    args = parser.parse_args()
    if not args.run_id.isalnum():
        parser.error("run-id must contain only letters and digits")
    requests = []
    tool_observed = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.reply({"object": "list", "data": [{"id": "promethe-mock", "object": "model", "owned_by": "local"}]})

        def reply(self, payload):
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length > 2_000_000:
                self.send_error(413)
                return
            request = json.loads(self.rfile.read(length))
            if not self.path.endswith("/chat/completions"):
                self.send_error(404)
                return
            result_seen = any(m.get("role") == "tool" and "FIXTURE_READ_VERIFIED" in str(m.get("content")) for m in request.get("messages", []))
            if result_seen:
                tool_observed.append(True)
            requests.append({"path": self.path, "stream": request.get("stream", False),
                             "tools": request.get("tools", []), "messageCount": len(request.get("messages", []))})
            if len(requests) > 4:
                self.send_error(429, "Probe request budget exhausted")
                return
            base = {"id": "local-probe", "created": 1, "model": "promethe-mock"}
            call = None
            if args.scenario == "tool" and not result_seen:
                tool_name = "str_replace_editor" if args.harness == "deepseek" else "read_file"
                params = {"path": str(fixture / "fixture.txt")}
                if args.harness == "deepseek":
                    params["command"] = "view"
                call = {"id": "fixture-read", "type": "function", "function": {"name": tool_name, "arguments": json.dumps(params)}}
            message = {"role": "assistant", "content": SENTINEL}
            delta = message
            finish_reason = "stop"
            if call:
                message = {"role": "assistant", "content": None, "tool_calls": [call]}
                delta = {"role": "assistant", "tool_calls": [{"index": 0, **call}]}
                finish_reason = "tool_calls"
            if request.get("stream"):
                chunks = [
                    {**base, "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
                    {**base, "object": "chat.completion.chunk", "choices": [{"index": 0, "delta": {}, "finish_reason": finish_reason}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}},
                ]
                data = ("".join("data: " + json.dumps(item) + "\n\n" for item in chunks) + "data: [DONE]\n\n").encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            else:
                self.reply({**base, "object": "chat.completion", "choices": [{"index": 0, "message": message, "finish_reason": finish_reason}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    output = ROOT / "build/reports/remediation"
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    report = {"harness": args.harness, "scenario": args.scenario, "runId": args.run_id, "paidCalls": 0,
              "scope": "Upstream runtime transport and bounded lifecycle only; no model quality comparison"}
    try:
        with tempfile.TemporaryDirectory(prefix="promethe-probe-", dir=ROOT / "build/experiments") as tmp:
            home = Path(tmp)
            fixture = home / "workspace"
            fixture.mkdir()
            (fixture / "fixture.txt").write_text("FIXTURE_READ_VERIFIED")
            base_url = f"http://127.0.0.1:{server.server_port}/v1"
            env = {key: value for key, value in os.environ.items()
                   if key.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "SYSTEMDRIVE"}}
            env.update(HOME=str(home), USERPROFILE=str(home), HERMES_HOME=str(home / "hermes"),
                       DO_NOT_TRACK="1", NO_PROXY="127.0.0.1,localhost", PYTHONIOENCODING="utf-8")
            prompt = "Reply with a greeting. Do not use tools. This is a local transport probe."
            if args.scenario == "tool":
                prompt = "Read fixture.txt in the current workspace with the file reading tool, then report its contents."
            if args.harness == "deepseek":
                report["version"] = "deepseek-harness-sdk/runtime-bin 0.1.2rc1"
                code = """import json,sys
from deepseek_harness import DeepSeekHarness
with DeepSeekHarness(provider='deepseek-official', model='promethe-mock', profile='sdk-minimal', base_url=sys.argv[1], api_key='promethe-local-mock', cwd=sys.argv[2], dsh_home=sys.argv[3], initialize_timeout_seconds=30, request_timeout_seconds=30) as harness:
 result=harness.run(sys.argv[4],session_id='promethe-001')
 print(json.dumps({'final':result.final_response,'finishReason':result.finish_reason,'eventCount':len(result.events)}))
"""
                command = [str(ROOT / "build/experiments/dsh-venv/Scripts/python.exe"), "-c", code, base_url, str(fixture), str(home / "dsh"), prompt]
            else:
                report["version"] = "v2026.8.31"
                hermes_home = Path(env["HERMES_HOME"])
                hermes_home.mkdir()
                (hermes_home / "config.yaml").write_text(json.dumps({"model": {"provider": "custom", "default": "promethe-mock", "base_url": base_url, "api_key": "promethe-local-mock", "api_mode": "chat_completions"}}))
                query = home / "prompt.txt"
                query.write_text(prompt)
                command = [str(ROOT / "build/experiments/hermes/hermes-agent-2026.8.31/.venv/Scripts/hermes.exe"), "chat", "--provider", "custom", "--model", "promethe-mock", "--query-file", str(query), "--oneshot", "--max-turns", "4", "--run-budget", "30"]
            completed = subprocess.run(command, cwd=fixture, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90)
            report.update(exitCode=completed.returncode, passed=completed.returncode == 0 and SENTINEL in completed.stdout and bool(requests) and (args.scenario != "tool" or bool(tool_observed)),
                          stdout=completed.stdout[-12000:], stderr=completed.stderr[-12000:])
    except Exception as error:
        report.update(passed=False, error=str(error))
    finally:
        server.shutdown()
        server.server_close()
        report.update(elapsedSeconds=round(time.monotonic() - started, 3), requestCount=len(requests), requests=requests, toolResultObserved=bool(tool_observed))
        (output / f"{args.harness}-{args.scenario}-probe-{args.run_id}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in {"requests", "stdout", "stderr"}}))
    return 0 if report.get("passed") else 1


if __name__ == "__main__":
    sys.exit(main())
