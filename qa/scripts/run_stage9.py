#!/usr/bin/env python3
"""Bounded, offline-first AI regression gate with fresh JUnit evidence only."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.scripts.run_stage8 import stop, test_environment
from qa.scripts.run_with_report import sanitize, secret_values

CORPUS_PATH = ROOT / "qa/security/ai-stage9-corpus.json"
JAVA_CLASSES = {
    "Stage9PromptSecurityTest": 35,
    "Stage9ModelOutputSecurityTest": 11,
    "Stage9RuleSecurityTest": 7,
    "Stage9A2ASecurityTest": 11,
    "Stage9McpSecurityTest": 8,
    "McpResourceAuthorizerTest": 4,
    "Stage9ActionSecurityTest": 4,
    "AgentCheckpointSafetyIntegrationTest": 20,
}
REQUIRED_BATCHES = {
    "harness",
    "java",
    "python",
    "frontend",
    "frontend-build",
    "browser",
}


def source_fingerprint() -> str:
    digest = hashlib.sha256()
    files = [CORPUS_PATH, Path(__file__)] + [
        ROOT / name
        for name in (
            "Makefile",
            "pom.xml",
            "apps/trip-agent-api/pom.xml",
            "apps/web/package-lock.json",
            "apps/web/playwright.ai-security.config.ts",
            "apps/web/playwright.security.config.ts",
            "services/ai-retrieval-service/pyproject.toml",
        )
    ]
    for directory in (
        "apps/trip-agent-api/src",
        "services/ai-retrieval-service/src/trip_agent_ai",
        "services/ai-retrieval-service/tests",
        "packages/api-contract",
        "apps/web/src",
        "apps/web/e2e",
        "qa/tests",
    ):
        files.extend(
            path
            for path in (ROOT / directory).rglob("*")
            if path.suffix
            in {".java", ".py", ".json", ".yaml", ".yml", ".txt", ".ts", ".tsx"}
            and not {"node_modules", "dist", "__pycache__", "generated"}
            & set(path.parts)
        )
    for path in sorted(set(files)):
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def safe_text(text: str) -> str:
    corpus = json.loads(CORPUS_PATH.read_text())
    for group in ("direct", "indirect", "benign", "urls"):
        for case in corpus[group]:
            text = text.replace(case["text"], f"[SYNTHETIC:{case['id']}]")
    text = text.replace("test-service-token", "[SYNTHETIC_SERVICE_TOKEN]")
    text = text.replace("stage9-synthetic", "[SYNTHETIC_MCP_TOKEN]")
    text = re.sub(r"x{256,}", "[OVERSIZE_SYNTHETIC_TEXT]", text)
    return sanitize(text, secret_values())


def junit_records(paths: list[Path], output: Path) -> list[dict]:
    records = []
    for path in paths:
        try:
            tree = ET.parse(path)
            root = tree.getroot()
            for parent in root.iter():
                for child in list(parent):
                    if child.tag in {"properties", "system-out", "system-err"}:
                        parent.remove(child)
            projects = {
                id(case): suite.get("hostname", "")
                for suite in root.iter("testsuite")
                for case in suite.findall("testcase")
            }
            for case in root.iter("testcase"):
                failure = case.find("failure")
                error = case.find("error")
                state = (
                    "FAILED"
                    if failure is not None
                    else "ERROR"
                    if error is not None
                    else "SKIPPED"
                    if case.find("skipped") is not None
                    else "PASSED"
                )
                records.append(
                    {
                        "id": f"{projects.get(id(case), '')}:{case.get('classname', '')}::{case.get('name', '')}",
                        "status": state,
                        "durationSeconds": float(case.get("time", "0")),
                        "evidence": str(path.relative_to(output)),
                        "traceIds": [],
                        "taskIds": [],
                        "traceNote": "No live trace asserted; use test ID and JUnit evidence.",
                    }
                )
            path.write_text(
                safe_text(ET.tostring(root, encoding="unicode")), encoding="utf-8"
            )
            ET.parse(path)
        except (ET.ParseError, ValueError, OSError):
            records.append(
                {"id": path.name, "status": "ERROR", "reason": "Invalid JUnit evidence"}
            )
    return records


def gate(records: list[dict], batches: list[dict], gaps: list[dict]) -> dict:
    counts = {
        state: sum(item["status"] == state for item in records)
        for state in ("PASSED", "FAILED", "ERROR", "SKIPPED")
    }
    required = dict(JAVA_CLASSES)
    observed = {
        name: sum(name in item["id"].split("::")[0] for item in records)
        for name in required
    }
    missing = [name for name, count in required.items() if observed[name] != count]
    for name, expected, fragment in (
        ("python", 63, "test_stage9_ai_security"),
        ("frontend", 7, "stage9-ai-security"),
        ("browser", 2, "stage8-security"),
        ("browser-evidence", 2, "stage9-security"),
        ("harness", 8, "test_stage9_harness"),
    ):
        if sum(fragment in item["id"] for item in records) != expected:
            missing.append(name)
    if {item["id"] for item in batches} != REQUIRED_BATCHES or len(batches) != len(
        REQUIRED_BATCHES
    ):
        missing.append("required-batches")
    if len({item["id"] for item in records}) != len(records):
        missing.append("duplicate-test-ids")
    passed = bool(records) and not missing and not gaps
    passed = passed and all(item["status"] == "PASSED" for item in records + batches)
    return {
        "status": "PASSED" if passed else "BLOCKED",
        "counts": counts,
        "missingOrChangedCoverage": missing,
        "gapCount": len(gaps),
    }


def run_batch(
    name: str, command: list[str], output: Path, env: dict, timeout: int = 600
) -> dict:
    started = time.monotonic()
    process = None
    status, code, text = "ERROR", None, ""
    try:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            raw, _ = process.communicate(timeout=timeout)
            code = process.returncode
            status = "PASSED" if code == 0 else "FAILED"
        except subprocess.TimeoutExpired:
            stop(process)
            raw, _ = process.communicate()
            status = "ERROR"
        text = raw.decode("utf-8", errors="replace")
    except OSError as error:
        text = type(error).__name__
    finally:
        if process is not None:
            stop(process)
    log = output / "logs" / f"{name}.log"
    log.write_text(safe_text(text), encoding="utf-8")
    return {
        "id": name,
        "status": status,
        "exitCode": code,
        "command": command,
        "durationSeconds": round(time.monotonic() - started, 3),
        "logLocation": str(log.relative_to(output)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", args.run) or args.run in {
        ".",
        "..",
    }:
        parser.error("--run must be a safe directory name")
    output = ROOT / "output/test-results" / args.run
    if output.exists():
        parser.error("Evidence directory exists; use a new run ID")
    for folder in ("json", "junit", "logs"):
        (output / folder).mkdir(parents=True)
    fingerprint = source_fingerprint()
    env = test_environment()
    env.update(
        {
            "QA_RUN_ID": args.run,
            "APP_ENV": "test",
            "DOCUMENT_PARSER_MODE": "mock",
            "RETRIEVAL_PROVIDER_MODE": "mock",
            "GRAPH_STORE_MODE": "memory",
            "DEEPSEEK_BASE_URL": "http://127.0.0.1:65535",
            "OTEL_ENABLED": "false",
            "PYTHONPATH": str(ROOT / "services/ai-retrieval-service/src"),
        }
    )
    for key in list(env):
        if key.startswith("VITE_"):
            env.pop(key)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        env["QA_WEB_BASE_URL"] = f"http://127.0.0.1:{listener.getsockname()[1]}"
    env["VITE_API_BASE_URL"] = env["QA_WEB_BASE_URL"]
    env["VITE_USER_AUTH_ENABLED"] = "false"
    py = str(ROOT / "services/ai-retrieval-service/.venv/bin/python")
    batches = [
        (
            "harness",
            [
                py,
                "-m",
                "pytest",
                "-q",
                "qa/tests/test_stage9_harness.py",
                f"--junitxml={output / 'junit/harness.xml'}",
            ],
        ),
        (
            "java",
            [
                "mvn",
                "-B",
                "-ntp",
                "-pl",
                "apps/trip-agent-api",
                "-Dtest=" + ",".join(JAVA_CLASSES),
                f"-Dsurefire.reportsDirectory={output / 'junit/java'}",
                "test",
            ],
        ),
        (
            "python",
            [
                py,
                "-m",
                "pytest",
                "-q",
                "--tb=short",
                "services/ai-retrieval-service/tests/test_stage9_ai_security.py",
                f"--junitxml={output / 'junit/python.xml'}",
            ],
        ),
        (
            "frontend",
            [
                "npm",
                "--prefix",
                "apps/web",
                "test",
                "--",
                "stage9-ai-security",
                "--reporter=default",
                "--reporter=junit",
                f"--outputFile={output / 'junit/frontend.xml'}",
            ],
        ),
        ("frontend-build", ["npm", "--prefix", "apps/web", "run", "build"]),
        (
            "browser",
            [
                "node",
                "apps/web/node_modules/playwright/cli.js",
                "test",
                "--config=apps/web/playwright.ai-security.config.ts",
            ],
        ),
    ]
    batch_results = []
    for name, command in batches:
        print(f"Stage 9: {name}", flush=True)
        if name == "browser" and batch_results[-1]["status"] != "PASSED":
            batch_results.append(
                {"id": name, "status": "ERROR", "reason": "Fresh build failed"}
            )
            continue
        batch_results.append(run_batch(name, command, output, env))
        (output / "json/batches.json").write_text(
            json.dumps(batch_results, indent=2) + "\n"
        )
    records = junit_records(sorted((output / "junit").rglob("*.xml")), output)
    for path in (output / "junit").rglob("*.txt"):
        path.write_text(safe_text(path.read_text()), encoding="utf-8")
    corpus = json.loads(CORPUS_PATH.read_text())
    gaps = [{**item, "status": "NOT_EXECUTED"} for item in corpus["gaps"]]
    if source_fingerprint() != fingerprint:
        gaps.append(
            {
                "id": "source-changed",
                "status": "NOT_EXECUTED",
                "reason": "Source changed during execution; rerun required",
            }
        )
    result = {
        "version": corpus["version"],
        "sourceSha256": fingerprint,
        "modelMode": "MOCK",
        "realModelExecution": "DISABLED_BY_CONFIGURATION",
        "gate": gate(records, batch_results, gaps),
        "batches": batch_results,
        "cases": records,
        "gaps": gaps,
    }
    (output / "json/stage9-summary.json").write_text(
        json.dumps(result, indent=2) + "\n"
    )
    suite = ET.Element(
        "testsuite",
        name="stage9-release",
        tests="1",
        failures="0" if result["gate"]["status"] == "PASSED" else "1",
    )
    case = ET.SubElement(suite, "testcase", name="complete-ai-security-gate")
    if result["gate"]["status"] != "PASSED":
        ET.SubElement(
            case, "failure", message="AI security regression is blocked"
        ).text = json.dumps(result["gate"])
    ET.ElementTree(suite).write(output / "json/stage9-release.xml", encoding="utf-8")
    print(json.dumps(result["gate"]), flush=True)
    return int(result["gate"]["status"] != "PASSED")


if __name__ == "__main__":
    raise SystemExit(main())
