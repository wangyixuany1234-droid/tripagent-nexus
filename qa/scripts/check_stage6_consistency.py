#!/usr/bin/env python3
"""Run read-only stage-six consistency checks against an authorized database."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import unquote, urlparse

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--database-url",
        default=os.getenv("QA_DATABASE_URL", ""),
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=REPOSITORY_ROOT / "qa/config/consistency-thresholds.json",
    )
    parser.add_argument(
        "--sql",
        type=Path,
        default=REPOSITORY_ROOT / "qa/consistency/stage6_consistency.sql",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--psql", default=os.getenv("PSQL_BIN", "psql"))
    return parser.parse_args()


def database_environment(database_url: str) -> dict[str, str]:
    parsed = urlparse(database_url)
    if parsed.scheme not in {"postgres", "postgresql"}:
        raise ValueError("QA_DATABASE_URL must use postgresql://")
    if not parsed.hostname or not parsed.path.removeprefix("/"):
        raise ValueError("QA_DATABASE_URL must include host and database")
    authorized = os.getenv("QA_AUTHORIZED_TEST_ENV", "false").lower() == "true"
    if parsed.hostname not in LOCAL_HOSTS and not authorized:
        raise ValueError(
            "Remote consistency checks require QA_AUTHORIZED_TEST_ENV=true"
        )
    environment = {
        **os.environ,
        "PGHOST": parsed.hostname,
        "PGPORT": str(parsed.port or 5432),
        "PGDATABASE": parsed.path.removeprefix("/"),
        "PGCONNECT_TIMEOUT": os.getenv("PGCONNECT_TIMEOUT", "5"),
    }
    if parsed.username:
        environment["PGUSER"] = unquote(parsed.username)
    if parsed.password:
        environment["PGPASSWORD"] = unquote(parsed.password)
    return environment


def parse_rows(stdout: str) -> list[dict[str, object]]:
    checks: list[dict[str, object]] = []
    for line in stdout.splitlines():
        if not line.strip():
            continue
        name, raw_count, raw_samples = line.split("\t", 2)
        checks.append(
            {
                "name": name,
                "violationCount": int(raw_count),
                "sampleIds": json.loads(raw_samples),
            }
        )
    return checks


def wait_for_consistency(
    command: list[str],
    environment: dict[str, str],
    maximum_violations: int,
    timeout_seconds: float,
    poll_interval_seconds: float,
    *,
    runner: Callable[..., subprocess.CompletedProcess[str]] | None = None,
    clock: Callable[[], float] | None = None,
    sleeper: Callable[[float], None] | None = None,
) -> tuple[list[dict[str, object]], dict[str, object]]:
    if timeout_seconds < 0 or poll_interval_seconds <= 0:
        raise ValueError("Consistency timing thresholds must be positive")
    runner = runner or subprocess.run
    clock = clock or time.monotonic
    sleeper = sleeper or time.sleep
    started_at = clock()
    attempts = 0

    while True:
        completed = runner(
            command,
            cwd=REPOSITORY_ROOT,
            env=environment,
            check=True,
            capture_output=True,
            text=True,
            timeout=60,
        )
        attempts += 1
        checks = parse_rows(completed.stdout)
        total = sum(int(check["violationCount"]) for check in checks)
        elapsed_seconds = clock() - started_at
        converged = total <= maximum_violations and elapsed_seconds <= timeout_seconds
        if converged or elapsed_seconds >= timeout_seconds:
            return checks, {
                "converged": converged,
                "elapsedMilliseconds": round(elapsed_seconds * 1000),
                "attempts": attempts,
                "timeoutMilliseconds": round(timeout_seconds * 1000),
            }
        sleeper(min(poll_interval_seconds, timeout_seconds - elapsed_seconds))


def main() -> None:
    args = parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    report: dict[str, object] = {
        "stage": 6,
        "generatedAt": datetime.now(UTC).isoformat(),
        "status": "FAILED",
        "thresholds": config,
        "checks": [],
    }
    try:
        environment = database_environment(args.database_url)
        command = [
            args.psql,
            "-X",
            "-A",
            "-t",
            "-F",
            "\t",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            f"stale_outbox_seconds={int(config['staleOutboxSeconds'])}",
            "-v",
            f"long_running_task_seconds={int(config['longRunningTaskSeconds'])}",
            "-v",
            f"stale_inbox_seconds={int(config['staleInboxSeconds'])}",
            "-v",
            f"sample_limit={int(config['sampleLimit'])}",
            "-f",
            str(args.sql),
        ]
        maximum = int(config["maximumViolations"])
        checks, convergence = wait_for_consistency(
            command,
            environment,
            maximum,
            float(config["eventualConsistencyTimeoutSeconds"]),
            float(config["pollIntervalSeconds"]),
        )
        total = sum(int(check["violationCount"]) for check in checks)
        report.update(
            {
                "status": "PASSED" if convergence["converged"] else "FAILED",
                "totalViolations": total,
                "checks": checks,
                "convergence": convergence,
            }
        )
    except (
        FileNotFoundError,
        KeyError,
        ValueError,
        subprocess.SubprocessError,
    ) as exception:
        report["failure"] = {
            "errorType": type(exception).__name__,
            "message": "Consistency checks could not be completed.",
        }
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    raise SystemExit(0 if report["status"] == "PASSED" else 1)


if __name__ == "__main__":
    main()
