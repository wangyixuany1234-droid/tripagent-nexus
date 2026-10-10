#!/usr/bin/env python3
"""Start an isolated mock FastAPI target and scan it with the built web assets."""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import httpx

from qa.scripts.run_stage8 import stop, test_environment
from qa.security.stage8_support import load_config, write_records
from qa.security.stage8_zap import free_port, run


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    if Path(args.run).name != args.run:
        parser.error("--run must be a directory name")
    output = ROOT / "output/test-results" / args.run
    for child in ("json", "logs"):
        (output / child).mkdir(parents=True, exist_ok=True)
    config = load_config()
    records = []
    port = free_port()
    with tempfile.TemporaryDirectory(
        prefix="fastapi-", dir=ROOT / ".runtime/stage8"
    ) as temporary:
        env = test_environment()
        env.update(
            {
                "PYTHONPATH": str(ROOT / "services/ai-retrieval-service/src"),
                "APP_ENV": "test",
                "NEO4J_PASSWORD": "test-neo4j-password",
                "NEO4J_URI": "bolt://127.0.0.1:65535",
                "DEEPSEEK_BASE_URL": "http://127.0.0.1:65535",
                "ALLOWED_EXTERNAL_HOSTS": "127.0.0.1,localhost",
                "DOCUMENT_PARSER_MODE": "mock",
                "RETRIEVAL_PROVIDER_MODE": "mock",
                "GRAPH_STORE_MODE": "memory",
                "OTEL_ENABLED": "false",
                "LANCEDB_URI": str(Path(temporary) / "lancedb"),
            }
        )
        with (output / "logs/stage8-fastapi.log").open("w") as log:
            process = subprocess.Popen(
                [
                    str(ROOT / "services/ai-retrieval-service/.venv/bin/python"),
                    "-m",
                    "uvicorn",
                    "trip_agent_ai.main:app",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--no-access-log",
                ],
                env=env,
                cwd=temporary,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                base = f"http://127.0.0.1:{port}"
                with httpx.Client(
                    base_url=base,
                    trust_env=False,
                    timeout=config["requestTimeoutSeconds"],
                ) as client:
                    deadline = time.monotonic() + config["serverStartupSeconds"]
                    while time.monotonic() < deadline:
                        try:
                            if (
                                client.get("/internal/v1/runtime-metrics").status_code
                                == 401
                            ):
                                break
                        except httpx.HTTPError:
                            pass
                        if process.poll() is not None:
                            raise RuntimeError("FastAPI exited before readiness")
                        time.sleep(0.2)
                    else:
                        raise TimeoutError("FastAPI startup deadline")
                    for name, headers, expected in [
                        ("anonymous", {}, 401),
                        ("invalid", {"Authorization": "Bearer invalid"}, 401),
                        (
                            "validService",
                            {"Authorization": "Bearer test-service-token"},
                            200,
                        ),
                    ]:
                        response = client.get(
                            "/internal/v1/runtime-metrics", headers=headers
                        )
                        records.append(
                            {
                                "id": "fastapi.auth." + name,
                                "status": "PASSED"
                                if response.status_code == expected
                                else "FAILED",
                                "httpStatus": response.status_code,
                                "traceId": response.headers.get("x-trace-id"),
                                "scope": "Internal service credential, not end-user JWT.",
                            }
                        )
                write_records(output / "json", "stage8-fastapi-http", records)
                records.extend(
                    run(
                        {
                            "baseUrl": base,
                            "paths": [
                                "/internal/v1/runtime-metrics?stage8Probe=synthetic",
                                "/internal/v1/health",
                                "/internal/v1/not-found",
                            ],
                        },
                        output / "json",
                        config,
                    )
                )
                return int(any(r["status"] in {"FAILED", "ERROR"} for r in records))
            finally:
                stop(process)


if __name__ == "__main__":
    raise SystemExit(main())
