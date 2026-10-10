#!/usr/bin/env python3
"""Run bounded local fault suites and keep a separate report for every command."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]


def require_local_docker(env: dict[str, str]) -> None:
    host = env.get("DOCKER_HOST", "")
    if not host:
        context = subprocess.run(
            ["docker", "context", "inspect"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        host = json.loads(context.stdout)[0]["Endpoints"]["docker"]["Host"]
    parsed = urlparse(host)
    if (
        parsed.scheme != "unix"
        and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
        and env.get("QA_AUTHORIZED_TEST_ENV") != "true"
    ):
        raise RuntimeError("Remote Docker requires QA_AUTHORIZED_TEST_ENV=true")
    subprocess.run(
        ["docker", "info"], check=True, stdout=subprocess.DEVNULL, timeout=20, env=env
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--services-only", action="store_true")
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    env = os.environ.copy()
    env.update(
        {
            "REAL_MODEL_TEST_ENABLED": "false",
            "DEEPSEEK_API_KEY": "",
            "MODEL_GATEWAY_PROVIDER": "mock",
            "MCP_PROVIDER_MODE": "MOCK",
            "QA_REQUEST_SUMMARY": "阶段七本地合成故障，禁止第三方模型或工具调用",
        }
    )
    env.setdefault("QA_RUN_ID", datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ-stage7"))
    env.setdefault("QA_RESULTS_ROOT", str(ROOT / "output/test-results"))
    run = (Path(env["QA_RESULTS_ROOT"]) / env["QA_RUN_ID"]).resolve()
    env["QA_CHAOS_OUTPUT"] = str(run / "experiments")
    python = ROOT / "services/ai-retrieval-service/.venv/bin/python"
    if not python.is_file():
        raise SystemExit("先安装智能服务测试依赖并创建 .venv")
    if not args.services_only:
        require_local_docker(env)
    failed = False
    commands = [
        (
            "java-services",
            [
                "mvn",
                "-B",
                "-ntp",
                "-pl",
                "apps/trip-agent-api",
                "-Dtest=ExternalCallChaosTest,FastApiChaosTest,ModelChaosTest,ModelCallExecutorTest,McpChaosTest,A2AChaosTest,Stage7TaskRecoveryChaosTest,Stage7McpTransportChaosTest,AgentResumeReproductionTest,AgentCheckpointSafetyIntegrationTest",
                f"-Dchaos.output={run / 'experiments'}",
                f"-Dsurefire.reportsDirectory={run / 'junit/java-services'}",
                "test",
            ],
        ),
        (
            "python-retrieval",
            [
                str(python),
                "-m",
                "pytest",
                "services/ai-retrieval-service/tests/test_retrieval_api.py",
                "-k",
                "stage7",
                "-q",
                f"--junitxml={run / 'junit/python-retrieval.xml'}",
            ],
        ),
    ]
    if not args.services_only:
        commands.extend(
            [
                (
                    "java-containers",
                    [
                        "mvn",
                        "-B",
                        "-ntp",
                        "-pl",
                        "apps/trip-agent-api",
                        "-Dtest=Stage7StorageChaosTestcontainersIntegrationTest,Stage7KafkaChaosTestcontainersIntegrationTest,Stage7ProcessRecoveryTestcontainersIntegrationTest",
                        f"-Dchaos.output={run / 'experiments'}",
                        f"-Dsurefire.reportsDirectory={run / 'junit/java-containers'}",
                        "test",
                    ],
                ),
                (
                    "neo4j-network",
                    [str(python), str(ROOT / "qa/chaos/neo4j_network.py")],
                ),
            ]
        )
    for suite, command in commands:
        result = subprocess.run(
            [
                str(python),
                str(ROOT / "qa/scripts/run_with_report.py"),
                "--suite",
                suite,
                "--",
                *command,
            ],
            cwd=ROOT,
            env=env,
            check=False,
        )
        failed = failed or result.returncode != 0
    summary = subprocess.run(
        [
            str(python),
            str(ROOT / "qa/scripts/summarize_stage7.py"),
            "--run",
            str(run),
            *(["--require-complete"] if args.require_complete else []),
        ],
        cwd=ROOT,
        env=env,
        check=False,
    )
    raise SystemExit(1 if failed else summary.returncode)


if __name__ == "__main__":
    main()
