#!/usr/bin/env python3
"""Run a test command and persist a sanitized machine-readable report."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SENSITIVE_ENV_NAMES = (
    "DEEPSEEK_API_KEY",
    "AI_SERVICE_TOKEN",
    "MCP_SERVICE_TOKEN",
    "ASYNC_OPERATIONS_TOKEN",
    "POSTGRES_PASSWORD",
    "REDIS_PASSWORD",
    "NEO4J_PASSWORD",
)
REDACTION_RULES = (
    (re.compile(r"(?i)(authorization\s*[:=]\s*bearer\s+)[^\s\"']+"), r"\1[REDACTED]"),
    (
        re.compile(r"(?i)((?:api[-_]?key|token|password)\s*[:=]\s*)[^\s,\"']+"),
        r"\1[REDACTED]",
    ),
    (re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "[REDACTED_PHONE]"),
    (
        re.compile(r"(?<![A-Za-z0-9])\d{17}[\dXx](?![A-Za-z0-9])"),
        "[REDACTED_ID]",
    ),
)
TRACE_ID = re.compile(r'(?i)\btraceId["\s:=]+([0-9a-f]{16,32})\b')
TASK_ID = re.compile(r'(?i)\btaskId["\s:=]+([0-9a-f]{8}-[0-9a-f-]{27,36})\b')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    parser.add_argument(
        "--reports-dir",
        default=os.getenv(
            "QA_RESULTS_ROOT",
            str(REPOSITORY_ROOT / "output/test-results"),
        ),
    )
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command and args.command[0] == "--":
        args.command = args.command[1:]
    if not args.command:
        parser.error("a command is required after --")
    return args


def secret_values() -> list[str]:
    return [
        value
        for name in SENSITIVE_ENV_NAMES
        if (value := os.getenv(name)) and len(value) >= 4
    ]


def sanitize(value: str, secrets: list[str]) -> str:
    result = value
    for secret in secrets:
        result = result.replace(secret, "[REDACTED]")
    for pattern, replacement in REDACTION_RULES:
        result = pattern.sub(replacement, result)
    return result


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPOSITORY_ROOT))
    except ValueError:
        return str(path)


def collect_identifiers(
    target: set[str],
    pattern: re.Pattern[str],
    value: str,
    limit: int = 20,
) -> None:
    if len(target) >= limit:
        return
    for identifier in pattern.findall(value):
        target.add(identifier)
        if len(target) >= limit:
            return


def main() -> None:
    args = parse_args()
    secrets = secret_values()
    started = datetime.now(timezone.utc)
    report_run = os.getenv("QA_RUN_ID") or started.strftime("%Y%m%dT%H%M%SZ")
    report_dir = Path(args.reports_dir) / report_run
    log_dir = report_dir / "logs"
    json_dir = report_dir / "json"
    log_dir.mkdir(parents=True, exist_ok=True)
    json_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"{args.suite}.log"
    report_path = json_dir / f"{args.suite}.json"
    sanitized_command = [sanitize(part, secrets) for part in args.command]
    trace_ids: set[str] = set()
    task_ids: set[str] = set()
    start_monotonic = time.monotonic()

    with log_path.open("w", encoding="utf-8") as log_file:
        process = subprocess.Popen(
            args.command,
            cwd=REPOSITORY_ROOT,
            env=os.environ.copy(),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert process.stdout is not None
        for raw_line in process.stdout:
            line = sanitize(raw_line, secrets)
            collect_identifiers(trace_ids, TRACE_ID, line)
            collect_identifiers(task_ids, TASK_ID, line)
            log_file.write(line)
            log_file.flush()
            sys.stdout.write(line)
            sys.stdout.flush()
        return_code = process.wait()

    finished = datetime.now(timezone.utc)
    report = {
        "suite": args.suite,
        "status": "passed" if return_code == 0 else "failed",
        "exitCode": return_code,
        "startedAt": started.isoformat(),
        "finishedAt": finished.isoformat(),
        "durationSeconds": round(time.monotonic() - start_monotonic, 3),
        "command": sanitized_command,
        "requestSummary": sanitize(
            os.getenv("QA_REQUEST_SUMMARY", "未提供请求摘要"), secrets
        )[:500],
        "failureContext": {
            "traceIds": sorted(trace_ids),
            "taskIds": sorted(task_ids),
            "logLocation": display_path(log_path),
        },
    }
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    raise SystemExit(return_code)


if __name__ == "__main__":
    main()
