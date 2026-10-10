#!/usr/bin/env python3
"""Stage-eight CI entry point; independent batches, bounded time, explicit gaps."""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.scripts.run_stage8 import stop, test_environment
from qa.scripts.run_with_report import sanitize, secret_values
from qa.security.stage8_support import load_config, write_records


def run_batch(
    name: str, command: list[str], output: Path, env: dict, timeout: int
) -> dict:
    started = time.monotonic()
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    status = "ERROR"
    try:
        try:
            text, _ = process.communicate(timeout=timeout)
            status = "PASSED" if process.returncode == 0 else "FAILED"
        except subprocess.TimeoutExpired:
            stop(process)
            text, _ = process.communicate()
        log = output / "logs" / f"{name}.log"
        log.write_text(
            sanitize(text.decode("utf-8", errors="replace"), secret_values())
        )
        return {
            "id": name,
            "status": status,
            "exitCode": process.returncode,
            "durationMs": round((time.monotonic() - started) * 1000),
            "command": command,
            "logLocation": str(log.relative_to(ROOT)),
        }
    finally:
        stop(process)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument(
        "--checks",
        nargs="+",
        choices=("static", "artifacts", "dynamic", "images"),
        default=["static", "artifacts", "dynamic", "images"],
    )
    args = parser.parse_args()
    if Path(args.run).name != args.run:
        parser.error("--run must be a directory name")
    output = ROOT / "output/test-results" / args.run
    for child in ("logs", "json", "junit"):
        (output / child).mkdir(parents=True, exist_ok=True)
    config = load_config()
    env = test_environment()
    env.update(
        {
            "QA_RUN_ID": args.run,
            "SEMGREP_SEND_METRICS": "off",
            "SEMGREP_ENABLE_VERSION_CHECK": "0",
            "SEMGREP_SETTINGS_FILE": str(
                ROOT / ".runtime/semgrep-config/settings.yaml"
            ),
            "SEMGREP_LOG_FILE": str(ROOT / ".runtime/semgrep-config/stage8.log"),
            "UV_CACHE_DIR": str(ROOT / ".runtime/uv-cache"),
        }
    )
    py = sys.executable
    trivy = str(ROOT / ".runtime/tools/trivy")
    leaks = str(ROOT / ".runtime/tools/gitleaks")
    findings = output / "json"
    batches = []
    if "static" in args.checks:
        batches.extend(
            [
                (
                    "semgrep",
                    [
                        str(ROOT / "services/ai-retrieval-service/.venv/bin/uvx"),
                        "--from",
                        "semgrep==1.136.0",
                        "--with",
                        "setuptools<81",
                        "semgrep",
                        "scan",
                        "--config",
                        "qa/security/semgrep.yml",
                        "--config",
                        "p/security-audit",
                        "--metrics",
                        "off",
                        "--disable-version-check",
                        "--error",
                        "--json-output",
                        str(findings / "semgrep-findings.json"),
                        "--exclude",
                        "node_modules",
                        "--exclude",
                        "target",
                        "--exclude",
                        ".venv",
                        "apps",
                        "services",
                    ],
                ),
                (
                    "gitleaks-current",
                    [
                        leaks,
                        "dir",
                        ".",
                        "--config",
                        ".gitleaks.toml",
                        "--redact",
                        "--no-banner",
                        "--report-format",
                        "json",
                        "--report-path",
                        str(findings / "gitleaks-current-findings.json"),
                    ],
                ),
                (
                    "trivy-filesystem",
                    [
                        trivy,
                        "fs",
                        "--cache-dir",
                        str(ROOT / ".runtime/trivy-cache"),
                        "--timeout",
                        f"{config['scannerTimeoutSeconds']}s",
                        "--scanners",
                        "vuln,misconfig",
                        "--exit-code",
                        "1",
                        "--skip-dirs",
                        "node_modules",
                        "--skip-dirs",
                        "target",
                        "--skip-dirs",
                        ".venv",
                        "--skip-dirs",
                        ".runtime",
                        "--skip-dirs",
                        "output",
                        "--format",
                        "json",
                        "--output",
                        str(findings / "trivy-findings.json"),
                        ".",
                    ],
                ),
                (
                    "npm-audit",
                    [
                        "npm",
                        "--prefix",
                        "apps/web",
                        "audit",
                        "--json",
                        "--audit-level=high",
                    ],
                ),
                (
                    "pip-audit",
                    [
                        str(ROOT / "qa/scripts/audit_python_dependencies.sh"),
                        str(findings / "pip-audit-findings.json"),
                    ],
                ),
                (
                    "java-sbom",
                    [
                        "mvn",
                        "-B",
                        "-ntp",
                        "-pl",
                        "apps/trip-agent-api",
                        "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom",
                        "-DincludeTestScope=false",
                        "-DincludeProvidedScope=false",
                        "-DoutputFormat=json",
                        f"-DoutputDirectory={findings}",
                        "-DoutputName=java-runtime-components",
                    ],
                ),
                (
                    "trivy-java-sbom",
                    [
                        trivy,
                        "sbom",
                        "--cache-dir",
                        str(ROOT / ".runtime/trivy-cache"),
                        "--timeout",
                        f"{config['scannerTimeoutSeconds']}s",
                        "--scanners",
                        "vuln",
                        "--exit-code",
                        "1",
                        "--format",
                        "json",
                        "--output",
                        str(findings / "trivy-java-sbom-findings.json"),
                        str(findings / "java-runtime-components.json"),
                    ],
                ),
            ]
        )
    if "artifacts" in args.checks:
        batches.extend(
            [
                (
                    "artifacts",
                    [py, "qa/scripts/check_stage8_artifacts.py", "--run", args.run],
                ),
                (
                    "gitleaks-build",
                    [
                        leaks,
                        "dir",
                        "apps/web/dist",
                        "--config",
                        "qa/security/gitleaks-artifacts.toml",
                        "--redact",
                        "--no-banner",
                        "--report-format",
                        "json",
                        "--report-path",
                        str(findings / "gitleaks-build-findings.json"),
                    ],
                ),
            ]
        )
    if "dynamic" in args.checks:
        batches.extend(
            [
                (
                    "java-boundary",
                    [
                        "mvn",
                        "-B",
                        "-ntp",
                        "-pl",
                        "apps/trip-agent-api",
                        "-Dtest=Stage8SecurityBoundaryTest,UploadPolicyTest,SecurityPropertiesTest,DeveloperTraceServiceTest,PromptInjectionGuardTest,SensitiveDataSanitizerTest",
                        f"-Dsurefire.reportsDirectory={output / 'junit/java-security'}",
                        "test",
                    ],
                ),
                (
                    "python-security",
                    [
                        py,
                        "-m",
                        "pytest",
                        "-q",
                        "services/ai-retrieval-service/tests/test_stage8_security.py",
                        "services/ai-retrieval-service/tests/test_security_concurrency.py",
                        "services/ai-retrieval-service/tests/test_upload_policy.py",
                        f"--junitxml={output / 'junit/python-security.xml'}",
                    ],
                ),
                (
                    "java-http",
                    [
                        py,
                        "qa/scripts/run_stage8.py",
                        "--phase",
                        "api",
                        "--run",
                        args.run,
                    ],
                ),
                (
                    "zap-web-fastapi",
                    [py, "qa/scripts/run_stage8_web.py", "--run", args.run],
                ),
                (
                    "browser-security",
                    [
                        "node",
                        "apps/web/node_modules/playwright/cli.js",
                        "test",
                        "--config=apps/web/playwright.security.config.ts",
                    ],
                ),
            ]
        )
    records = []
    if not (ROOT / ".git").exists():
        records.append(
            {
                "id": "gitleaks-history",
                "status": "NOT_EXECUTED",
                "reason": "Workspace has no Git history.",
            }
        )
    elif "static" in args.checks:
        batches.append(
            (
                "gitleaks-history",
                [
                    leaks,
                    "git",
                    ".",
                    "--redact",
                    "--no-banner",
                    "--report-format",
                    "json",
                    "--report-path",
                    str(findings / "gitleaks-history-findings.json"),
                ],
            )
        )
    if "images" in args.checks:
        batches.append(
            ("images", [py, "qa/scripts/check_stage8_images.py", "--run", args.run])
        )
    for name, command in batches:
        print(f"Stage 8: {name}", flush=True)
        try:
            record = run_batch(
                name, command, output, env, config["batchTimeoutSeconds"]
            )
        except OSError as error:
            record = {
                "id": name,
                "status": "ERROR",
                "failureType": type(error).__name__,
            }
        records.append(record)
        write_records(findings, "stage8-batches", records)
    gate = run_batch(
        "release-summary",
        [py, "qa/scripts/summarize_stage8.py", "--run", str(output)],
        output,
        env,
        config["scannerTimeoutSeconds"],
    )
    records.append(gate)
    write_records(findings, "stage8-batches", records)
    # Missing batches, scanner errors and untriaged findings never produce a green CI run.
    return int(any(record["status"] != "PASSED" for record in records))


if __name__ == "__main__":
    raise SystemExit(main())
