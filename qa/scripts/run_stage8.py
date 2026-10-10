#!/usr/bin/env python3
"""Run independent local security batches without treating failures as passes."""

from __future__ import annotations

import argparse
import json
import os
import platform
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.security.stage8_support import load_config


def test_environment() -> dict:
    env = os.environ.copy()
    env.update(
        {
            "REAL_MODEL_TEST_ENABLED": "false",
            "DEEPSEEK_API_KEY": "",
            "MODEL_GATEWAY_PROVIDER": "mock",
            "MCP_PROVIDER_MODE": "MOCK",
            "OPENAI_API_KEY": "",
            "AI_SERVICE_TOKEN": "test-service-token",
            "OTEL_SDK_DISABLED": "true",
            "LC_ALL": "C",
            "LANG": "C",
            "LC_CTYPE": "C",
        }
    )
    return env


def stop(process: subprocess.Popen) -> None:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("api", "zap"), default="api")
    parser.add_argument("--run", default=os.getenv("QA_RUN_ID", "stage8-security"))
    args = parser.parse_args()
    if Path(args.run).name != args.run:
        parser.error("--run must be a directory name")
    config = load_config()
    output = ROOT / "output/test-results" / args.run
    for child in ("json", "logs", "junit", "html"):
        (output / child).mkdir(parents=True, exist_ok=True)
    runtime_root = ROOT / ".runtime/stage8"
    runtime_root.mkdir(parents=True, exist_ok=True)
    env = test_environment()
    env["QA_RUN_ID"] = args.run
    command = [
        "mvn",
        "-B",
        "-ntp",
        "-pl",
        "apps/trip-agent-api",
        "test-compile",
        "dependency:build-classpath",
        "-Dmdep.includeScope=test",
        f"-Dmdep.outputFile={runtime_root / 'classpath.txt'}",
    ]
    compile_result = subprocess.run(
        [
            sys.executable,
            str(ROOT / "qa/scripts/run_with_report.py"),
            "--suite",
            "stage8-compile",
            "--",
            *command,
        ],
        cwd=ROOT,
        env=env,
        timeout=config["batchTimeoutSeconds"],
        check=False,
    )
    if compile_result.returncode:
        return 2
    classpath = os.pathsep.join(
        [
            str(ROOT / "apps/trip-agent-api/target/test-classes"),
            str(ROOT / "apps/trip-agent-api/target/classes"),
            (runtime_root / "classpath.txt").read_text().strip(),
        ]
    )
    if sys.platform == "darwin" and platform.machine() == "arm64":
        binary = runtime_root / "embedded-postgres-binaries-darwin-arm64v8-17.11.0.jar"
        if not binary.exists():
            subprocess.run(
                [
                    "mvn",
                    "-B",
                    "-ntp",
                    "dependency:copy",
                    "-Dartifact=io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8:17.11.0",
                    f"-DoutputDirectory={runtime_root}",
                ],
                cwd=ROOT,
                env=env,
                timeout=180,
                check=True,
            )
        classpath += os.pathsep + str(binary)
    # Each invocation owns its database, upload root and child process group.
    with tempfile.TemporaryDirectory(
        prefix=f"{args.phase}-", dir=runtime_root
    ) as temporary:
        runtime = Path(temporary)
        with (output / "logs" / f"stage8-{args.phase}-server.log").open("w") as log:
            server = subprocess.Popen(
                [
                    "java",
                    "-Xmx512m",
                    f"-Dstage8.runtime={runtime}",
                    f"-Dstage8.lifetimeSeconds={config['maxServerLifetimeSeconds']}",
                    "-cp",
                    classpath,
                    "com.tripagent.nexus.testsupport.Stage8SecurityServer",
                ],
                cwd=ROOT,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + config["serverStartupSeconds"]
                while not (runtime / "server.json").exists():
                    if server.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError(
                            "Local synthetic security target did not become ready"
                        )
                    time.sleep(0.2)
                seed = json.loads((runtime / "server.json").read_text())
                evidence_seed = dict(seed)
                evidence_seed["authTokens"] = {
                    name: "[REDACTED_EPHEMERAL_TOKEN]"
                    for name in seed.get("authTokens", {})
                }
                (output / "json" / f"stage8-{args.phase}-seed.json").write_text(
                    json.dumps(evidence_seed, indent=2) + "\n"
                )
                if args.phase == "api":
                    from qa.security.stage8_api import run
                else:
                    from qa.security.stage8_zap import run
                records = run(seed, output / "json", config)
                counts = {
                    status: sum(r["status"] == status for r in records)
                    for status in (
                        "PASSED",
                        "FAILED",
                        "ERROR",
                        "NOT_APPLICABLE",
                        "NOT_EXECUTED",
                    )
                }
                print(json.dumps(counts))
                return 1 if counts["FAILED"] or counts["ERROR"] else 0
            finally:
                stop(server)


if __name__ == "__main__":
    raise SystemExit(main())
