#!/usr/bin/env python3
"""Inspect explicitly supplied local application images; never scan arbitrary hosts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.scripts.run_stage8 import test_environment
from qa.scripts.run_stage8_suite import run_batch
from qa.security.stage8_support import load_config, write_records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    if Path(args.run).name != args.run:
        parser.error("Invalid report directory")
    output = ROOT / "output/test-results" / args.run
    for child in ("logs", "json"):
        (output / child).mkdir(parents=True, exist_ok=True)
    config = load_config()
    records = []
    host = os.environ.get("DOCKER_HOST")
    if not host:
        context = subprocess.run(
            [
                "docker",
                "context",
                "inspect",
                "--format",
                "{{json .Endpoints.docker.Host}}",
            ],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        host = json.loads(context.stdout) if context.returncode == 0 else ""
    if not host.startswith("unix://"):
        raise ValueError("Only a local Unix Docker socket is allowed")
    available = subprocess.run(
        ["docker", "info", "--format", "{{.ServerVersion}}"],
        capture_output=True,
        text=True,
        timeout=15,
        check=False,
    )
    (output / "logs/docker-availability.log").write_text(
        available.stdout + available.stderr
    )
    if available.returncode:
        records.append(
            {
                "id": "images.application",
                "status": "NOT_EXECUTED",
                "reason": "Local Docker daemon unavailable; no final image or filesystem inspection.",
            }
        )
    else:
        for component in ("JAVA", "PYTHON", "WEB"):
            reference = os.getenv(f"QA_SECURITY_{component}_IMAGE")
            if not reference:
                records.append(
                    {
                        "id": "images." + component.lower(),
                        "status": "NOT_EXECUTED",
                        "reason": f"Set QA_SECURITY_{component}_IMAGE to the locally built release image.",
                    }
                )
                continue
            inspect = subprocess.run(
                ["docker", "image", "inspect", reference],
                capture_output=True,
                text=True,
                timeout=15,
                check=True,
            )
            image = json.loads(inspect.stdout)[0]
            records.append(
                {
                    "id": f"images.{component.lower()}.user",
                    "status": "PASSED"
                    if image["Config"].get("User", "").split(":")[0]
                    not in {"", "root", "0"}
                    else "FAILED",
                    "imageId": image["Id"],
                    "user": image["Config"].get("User"),
                }
            )
            records.append(
                run_batch(
                    f"trivy-image-{component.lower()}",
                    [
                        str(ROOT / ".runtime/tools/trivy"),
                        "image",
                        "--image-src",
                        "docker",
                        "--cache-dir",
                        str(ROOT / ".runtime/trivy-cache"),
                        "--scanners",
                        "vuln",
                        "--timeout",
                        f"{config['scannerTimeoutSeconds']}s",
                        "--format",
                        "json",
                        "--output",
                        str(output / f"json/trivy-{component.lower()}-image.json"),
                        "--exit-code",
                        "1",
                        image["Id"],
                    ],
                    output,
                    test_environment(),
                    config["scannerTimeoutSeconds"] + 15,
                )
            )
        records.append(
            {
                "id": "images.filesystem",
                "status": "NOT_EXECUTED",
                "reason": "Final image .env/test-key filesystem inspection requires separate evidence.",
            }
        )
    write_records(output / "json", "stage8-images", records)
    return int(any(item["status"] != "PASSED" for item in records))


if __name__ == "__main__":
    raise SystemExit(main())
