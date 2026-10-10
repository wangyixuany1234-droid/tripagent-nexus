#!/usr/bin/env python3
"""Inspect built artifacts and declared infrastructure without production access."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.security.stage8_support import load_config, write_records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    if Path(args.run).name != args.run:
        parser.error("--run must be a directory name")
    output = ROOT / "output/test-results" / args.run
    (output / "logs").mkdir(parents=True, exist_ok=True)
    records = []
    canary = "SYNTHETIC-NOT-A-CREDENTIAL-" + uuid.uuid4().hex
    env = {
        **os.environ,
        "VITE_USER_AUTH_ENABLED": "false",
        **{
            name: canary
            for name in (
                "DEEPSEEK_API_KEY",
                "AI_SERVICE_TOKEN",
                "MCP_SERVICE_TOKEN",
                "ASYNC_OPERATIONS_TOKEN",
            )
        },
    }
    build = subprocess.run(
        ["npm", "--prefix", "apps/web", "run", "build"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=load_config()["scannerTimeoutSeconds"],
    )
    (output / "logs/frontend-canary-build.log").write_text(
        (build.stdout + build.stderr).replace(canary, "[SYNTHETIC_CANARY]")
    )
    records.append(
        {
            "id": "build.canaryBuild",
            "status": "PASSED" if build.returncode == 0 else "ERROR",
            "exitCode": build.returncode,
        }
    )
    files = sorted(
        path for path in (ROOT / "apps/web/dist").rglob("*") if path.is_file()
    )
    matches = [
        str(path.relative_to(ROOT))
        for path in files
        if canary.encode() in path.read_bytes()
    ]
    records.append(
        {
            "id": "build.serverSecretIsolation",
            "status": "PASSED"
            if not matches and files and build.returncode == 0
            else "FAILED",
            "canaryFingerprint": hashlib.sha256(canary.encode()).hexdigest(),
            "matches": matches,
            "fileCount": len(files),
            "scope": "Synthetic backend environment canaries; combined with independent Gitleaks.",
        }
    )
    forbidden = [
        str(path.relative_to(ROOT))
        for path in files
        if path.name.startswith(".env") or path.suffix in {".pem", ".key", ".p12"}
    ]
    records.append(
        {
            "id": "build.credentialFiles",
            "status": "PASSED" if not forbidden else "FAILED",
            "matches": forbidden,
        }
    )
    manifest = [
        {
            "path": str(path.relative_to(ROOT)),
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in files
    ]
    (output / "json").mkdir(exist_ok=True)
    (output / "json/frontend-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n"
    )
    compose = yaml.safe_load((ROOT / "compose.yaml").read_text())
    for service in ("postgres", "redis", "kafka", "neo4j"):
        ports = compose["services"][service].get("ports", [])
        records.append(
            {
                "id": "infra.loopback." + service,
                "status": "PASSED"
                if ports and all(p.startswith("127.0.0.1:") for p in ports)
                else "FAILED",
                "ports": ports,
                "scope": "Compose declaration only, not a public network reachability scan.",
            }
        )
    for name, path in {
        "java": "apps/trip-agent-api/Dockerfile",
        "web": "apps/web/Dockerfile",
        "python": "services/ai-retrieval-service/Dockerfile",
    }.items():
        text = (ROOT / path).read_text()
        final_stage = re.split(r"(?im)^FROM ", text)[-1]
        users = re.findall(r"(?im)^USER\s+(\S+)", final_stage)
        records.append(
            {
                "id": "infra.nonroot." + name,
                "status": "PASSED"
                if users and users[-1].split(":")[0] not in {"0", "root"}
                else "FAILED",
                "declaredUser": users[-1] if users else None,
                "scope": "Dockerfile final stage; actual image inspection remains required.",
            }
        )
    write_records(output / "json", "stage8-artifacts", records)
    return int(any(record["status"] != "PASSED" for record in records))


if __name__ == "__main__":
    raise SystemExit(main())
