#!/usr/bin/env python3
"""Run the fixed Stage 10 intelligence evaluation three times."""

from __future__ import annotations

import argparse
import csv
import getpass
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from qa.scripts.run_with_report import sanitize, secret_values
from qa.scripts.stage10_metrics import (
    measured,
    not_measured,
    score_ocr,
    score_rag,
    summarize_repetitions,
)

DATASET_PATH = ROOT / "qa/fixtures/evaluation/stage10-v1.json"
PYTHON = ROOT / "services/ai-retrieval-service/.venv/bin/python"
JAVA_TESTS = {
    "AgentOrchestrationIntegrationTest": 3,
    "AgentSchemaValidatorTest": 5,
    "A2ADelegationServiceTest": 1,
    "A2AChaosTest": 3,
    "EvaluationReplayIntegrationTest": 1,
    "EndToEndPlanningIntegrationTest": 1,
}
EVALUATION_BASELINE = (
    ROOT / "apps/trip-agent-api/target/evaluation-reports/phase-9-v1-baseline.json"
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--repetitions", type=int)
    args = parser.parse_args()
    _validate_run_id(args.run, parser)

    dataset = json.loads(DATASET_PATH.read_text(encoding="utf-8"))
    repetitions = args.repetitions or int(dataset["repetitions"])
    if repetitions < 3 or repetitions > 10:
        parser.error("--repetitions must be between 3 and 10")
    output = ROOT / "output/test-results" / args.run
    if output.exists():
        parser.error("Evidence directory exists; use a new run ID")
    for folder in ("artifacts", "json", "junit", "logs", "manual-review"):
        (output / folder).mkdir(parents=True)

    initial_fingerprint = source_fingerprint()
    harness_batch = run_batch(
        "harness",
        [
            str(PYTHON),
            "-m",
            "pytest",
            "-q",
            "qa/tests/test_stage10_harness.py",
            f"--junitxml={output / 'junit/harness.xml'}",
        ],
        output,
        _test_environment(),
    )
    repetition_reports = []
    all_batches = [harness_batch]
    for repetition in range(1, repetitions + 1):
        print(f"测试阶段十：第 {repetition}/{repetitions} 轮", flush=True)
        report, batches = run_repetition(
            dataset,
            output,
            repetition,
        )
        repetition_reports.append(report)
        all_batches.extend(batches)
        _write_json(
            output / "json" / f"repetition-{repetition}-metrics.json",
            report,
        )
        _write_json(output / "json/batches.json", all_batches)

    real_report = {
        "mode": "REAL_MODEL",
        "status": "NOT_EXECUTED",
        "reason": "真实模型测试尚未获得本次显式授权。",
        "requiredControls": {
            "explicitAuthorization": "REAL_MODEL_TEST_ENABLED=true",
            "maximumConcurrency": 1,
            "maximumCalls": 5,
            "separateRunRequired": True,
        },
        "metrics": None,
    }
    _write_json(output / "json/real-model-report.json", real_report)
    write_manual_review(dataset, repetition_reports, output)

    gaps = build_gaps(
        dataset,
        repetition_reports,
        all_batches,
        initial_fingerprint,
    )
    execution_complete = all(
        batch["status"] == "PASSED" for batch in all_batches
    ) and all(report["status"] == "MEASURED" for report in repetition_reports)
    summary = {
        "schemaVersion": 1,
        "datasetVersion": dataset["version"],
        "datasetSha256": _sha256(DATASET_PATH),
        "sourceSha256": initial_fingerprint,
        "classification": dataset["classification"],
        "mockReport": {
            "mode": "MOCK",
            "status": "BASELINE_CAPTURED" if execution_complete else "INCOMPLETE",
            "qualityClaim": "MOCK_PIPELINE_ONLY",
            "repetitions": repetition_reports,
            "variation": summarize_repetitions(
                [report["metrics"] for report in repetition_reports]
            ),
        },
        "realModelReport": real_report,
        "configuration": {
            "versions": dataset["versions"],
            "retrievalParameters": dataset["retrievalParameters"],
            "random": dataset["random"],
            "runtime": {
                "python": platform.python_version(),
                "platform": platform.system(),
            },
        },
        "manualReview": {
            "status": "PENDING",
            "json": "manual-review/manual-review.json",
            "csv": "manual-review/manual-review.csv",
            "sampling": dataset["manualReview"]["sampling"],
        },
        "gaps": gaps,
        "gate": {
            "status": "BLOCKED_FOR_ACCEPTANCE" if execution_complete else "INCOMPLETE",
            "reason": (
                "未定义验收阈值、真实模型未授权且人工语义复核待完成。"
                if execution_complete
                else "一个或多个评测批次未完成。"
            ),
            "gapCount": len(gaps),
        },
    }
    _write_json(output / "json/stage10-summary.json", summary)
    print(json.dumps(summary["gate"], ensure_ascii=False), flush=True)
    return 1


def run_repetition(
    dataset: dict, output: Path, repetition: int
) -> tuple[dict, list[dict]]:
    raw_path = output / "json" / f"repetition-{repetition}-python-raw.json"
    artifact_dir = output / "artifacts" / f"repetition-{repetition}"
    runtime = ROOT / ".runtime/stage10" / output.name / f"repetition-{repetition}"
    env = _test_environment()
    python_batch = run_batch(
        f"python-repetition-{repetition}",
        [
            str(PYTHON),
            "qa/scripts/collect_stage10_python.py",
            "--dataset",
            str(DATASET_PATH),
            "--output",
            str(raw_path),
            "--artifacts",
            str(artifact_dir / "ocr"),
            "--runtime",
            str(runtime),
            "--repetition",
            str(repetition),
        ],
        output,
        env,
        timeout=300,
    )

    if EVALUATION_BASELINE.exists():
        EVALUATION_BASELINE.unlink()
    java_report_dir = output / "junit" / f"java-repetition-{repetition}"
    java_batch = run_batch(
        f"java-repetition-{repetition}",
        [
            "mvn",
            "-B",
            "-ntp",
            "-pl",
            "apps/trip-agent-api",
            "-Dtest=" + ",".join(JAVA_TESTS),
            f"-Dsurefire.reportsDirectory={java_report_dir}",
            "test",
        ],
        output,
        env,
        timeout=900,
    )
    evaluation_report = None
    if EVALUATION_BASELINE.is_file():
        copied = output / "json" / f"repetition-{repetition}-java-evaluation.json"
        shutil.copyfile(EVALUATION_BASELINE, copied)
        evaluation_report = json.loads(copied.read_text(encoding="utf-8"))

    observations = (
        json.loads(raw_path.read_text(encoding="utf-8"))
        if raw_path.is_file()
        else {"ocr": [], "rag": []}
    )
    junit = junit_records(sorted(java_report_dir.glob("TEST-*.xml")), output)
    for report_path in java_report_dir.glob("*.txt"):
        report_path.write_text(
            _safe_text(report_path.read_text(encoding="utf-8", errors="replace")),
            encoding="utf-8",
        )
    missing_junit = [
        name
        for name, expected in JAVA_TESTS.items()
        if sum(name in item["className"] for item in junit) != expected
    ]
    metrics = {
        "ocr": score_ocr(dataset, observations["ocr"]),
        "rag": score_rag(dataset, observations["rag"]),
        "agent": score_agent(dataset, junit, evaluation_report),
        "itinerary": score_itinerary(junit, evaluation_report),
    }
    status = (
        "MEASURED"
        if python_batch["status"] == "PASSED"
        and java_batch["status"] == "PASSED"
        and not missing_junit
        and evaluation_report is not None
        else "INCOMPLETE"
    )
    return (
        {
            "repetition": repetition,
            "status": status,
            "metrics": metrics,
            "junit": {
                "expectedTests": sum(JAVA_TESTS.values()),
                "observedTests": len(junit),
                "missingOrChangedClasses": missing_junit,
                "cases": junit,
            },
        },
        [python_batch, java_batch],
    )


def score_agent(dataset: dict, junit: list[dict], evaluation: dict | None) -> dict:
    evidence = dataset["agentEvidence"]
    coordinator = _test_passed(
        junit,
        "AgentOrchestrationIntegrationTest",
        "coordinatorDiscoversAgentsAndTransfersArrivalAndCosts",
    )
    e2e = _test_passed(
        junit,
        "EndToEndPlanningIntegrationTest",
        "beijingPlanningCreatesAlternativesRepairsConflictAndVersionsReplan",
    )
    timeout = _test_passed(
        junit,
        "A2ADelegationServiceTest",
        "returnsTimeoutWhenSubmittedTaskNeverReachesTerminalState",
    )
    recovery_passed = sum(
        item["status"] == "PASSED" and "A2AChaosTest" in item["className"]
        for item in junit
    )
    task_successes = int(coordinator) + int(e2e)
    structured = _evaluation_metric(evaluation, "structuredOutputValidityRate")
    token_total = _evaluation_number(evaluation, "tokenUsage")
    sample_size = evaluation.get("sampleSize") if evaluation else None
    return {
        "agentTaskSuccessRate": measured(task_successes, 2),
        "schemaValidityRate": _observed_metric(
            structured,
            evidence["validStructuredOutputs"],
            evidence["structuredOutputs"],
            "JAVA_EVALUATION_REPORT_MISSING",
        ),
        "toolSelectionAccuracy": measured(
            evidence["expectedNecessaryToolCalls"],
            evidence["expectedNecessaryToolCalls"],
        )
        if coordinator
        else not_measured("COORDINATOR_TOOL_ASSERTION_FAILED"),
        "agentTimeoutRate": measured(
            evidence["forcedTimeoutCases"],
            evidence["normalCoordinatorAgentTasks"] + evidence["forcedTimeoutCases"],
        )
        if timeout and coordinator
        else not_measured("TIMEOUT_OBSERVATION_INCOMPLETE"),
        "coordinatorRoutingErrorRate": measured(
            0, evidence["expectedCoordinatorRoutes"]
        )
        if coordinator
        else not_measured("COORDINATOR_ROUTE_ASSERTION_FAILED"),
        "partialFailureRecoveryRate": measured(
            recovery_passed, evidence["recoveryCases"]
        ),
        "unnecessaryToolCallCount": {
            **measured(0, 1, value=0),
            "unit": "calls",
        }
        if coordinator
        else not_measured("COORDINATOR_TOOL_ASSERTION_FAILED"),
        "averageTokenUsage": {
            **measured(
                token_total or 0,
                int(sample_size or 0),
            ),
            "unit": "tokensPerEvaluationCase",
        }
        if token_total is not None and sample_size
        else not_measured("TOKEN_USAGE_REPORT_MISSING"),
        "provenance": {
            "toolSelection": (
                "AgentOrchestrationIntegrationTest 精确断言 5 次必要工具调用集合。"
            ),
            "timeoutRate": "分母包含 8 个正常协调任务和 1 个故障注入超时任务。",
        },
    }


def score_itinerary(junit: list[dict], evaluation: dict | None) -> dict:
    e2e = _test_passed(
        junit,
        "EndToEndPlanningIntegrationTest",
        "beijingPlanningCreatesAlternativesRepairsConflictAndVersionsReplan",
    )
    return {
        "itineraryExecutabilityRate": _report_metric(
            evaluation, "itineraryExecutabilityRate"
        ),
        "timeConflictRate": measured(0, 1)
        if e2e
        else not_measured("FINAL_PLAN_CONFLICT_ASSERTION_FAILED"),
        "routeContinuityRate": measured(1, 1)
        if e2e
        else not_measured("COMMUTE_CONFLICT_ASSERTION_FAILED"),
        "openingHoursComplianceRate": measured(1, 1)
        if e2e
        else not_measured("OPENING_HOURS_ASSERTION_FAILED"),
        "budgetComplianceRate": measured(1, 1)
        if e2e
        else not_measured("FINAL_BUDGET_ASSERTION_FAILED"),
        "recommendationEvidenceCoverageRate": _report_metric(
            evaluation, "evidenceCoverageRate"
        ),
        "replanRepairRate": _report_metric(evaluation, "replanSuccessRate"),
        "lockedItemPreservationRate": measured(1, 1)
        if e2e
        else not_measured("LOCKED_ITEM_ASSERTION_FAILED"),
        "provenance": {
            "deterministicRules": [
                "Java BigDecimal budget assertion",
                "final open conflict code assertion",
                "locked item identity assertion",
            ]
        },
    }


def build_gaps(
    dataset: dict,
    reports: list[dict],
    batches: list[dict],
    initial_fingerprint: str,
) -> list[dict]:
    gaps = [
        {
            "id": "real-model-not-authorized",
            "status": "NOT_EXECUTED",
            "reason": "真实模型小流量评测需要单独显式授权。",
        },
        {
            "id": "mock-ocr-pixel-insensitive",
            "status": "LIMITATION",
            "reason": (
                "Mock OCR/视觉抽取按文件名返回固定结果，Mock 指标不能证明模糊、"
                "倾斜、深色、多语言或遮挡图片的真实识别能力。"
            ),
        },
        {
            "id": "manual-semantic-review-pending",
            "status": "PENDING_REVIEW",
            "reason": "OCR 与 RAG 语义样本尚待人工抽样复核。",
        },
        {
            "id": "acceptance-thresholds-undefined",
            "status": "PENDING_ACCEPTANCE",
            "reason": "用户尚未确认阶段十质量阈值，本次只能记录基线。",
        },
    ]
    for batch in batches:
        if batch["status"] != "PASSED":
            gaps.append(
                {
                    "id": f"batch-{batch['id']}",
                    "status": "EXECUTION_FAILED",
                    "reason": batch.get("reason") or batch.get("logLocation"),
                }
            )
    for report in reports:
        if report["status"] != "MEASURED":
            gaps.append(
                {
                    "id": f"repetition-{report['repetition']}-incomplete",
                    "status": "NOT_EXECUTED",
                    "reason": "该轮 Python 或 Java 证据不完整。",
                }
            )
    if source_fingerprint() != initial_fingerprint:
        gaps.append(
            {
                "id": "source-changed-during-run",
                "status": "INVALIDATED",
                "reason": "执行期间源文件发生变化，必须使用新运行 ID 重跑。",
            }
        )
    expected_ocr = {item["id"] for item in dataset["ocrCases"]}
    expected_rag = {item["id"] for item in dataset["ragCases"]}
    for report in reports:
        ocr_ids = {
            item["id"]
            for item in report["metrics"]["ocr"]["cases"]
            if item["status"] == "MEASURED"
        }
        rag_ids = {
            item["id"]
            for item in report["metrics"]["rag"]["cases"]
            if item["status"] == "MEASURED"
        }
        if ocr_ids != expected_ocr:
            gaps.append(
                {
                    "id": f"ocr-coverage-repetition-{report['repetition']}",
                    "status": "NOT_EXECUTED",
                    "reason": "OCR 固定数据集覆盖不完整。",
                }
            )
        if rag_ids != expected_rag:
            gaps.append(
                {
                    "id": f"rag-coverage-repetition-{report['repetition']}",
                    "status": "NOT_EXECUTED",
                    "reason": "RAG 固定数据集覆盖不完整。",
                }
            )
    return gaps


def write_manual_review(dataset: dict, reports: list[dict], output: Path) -> None:
    first = reports[0] if reports else {"metrics": {"ocr": {}, "rag": {}}}
    records: list[dict] = []
    for family in ("ocr", "rag"):
        for item in first["metrics"].get(family, {}).get("cases", []):
            records.append(
                {
                    "family": family.upper(),
                    "caseId": item["id"],
                    "scenario": item["scenario"],
                    "observation": item,
                    "reviewer": None,
                    "reviewedAt": None,
                    "semanticCorrect": None,
                    "notes": None,
                }
            )
    review = {
        "datasetVersion": dataset["version"],
        "status": "PENDING",
        "instructions": (
            "复核原始图片或查询与实际抽取/证据是否语义一致；不得由待测模型单独裁决。"
        ),
        "records": records,
    }
    _write_json(output / "manual-review/manual-review.json", review)
    with (output / "manual-review/manual-review.csv").open(
        "w", encoding="utf-8", newline=""
    ) as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "family",
                "caseId",
                "scenario",
                "reviewer",
                "reviewedAt",
                "semanticCorrect",
                "notes",
            ],
        )
        writer.writeheader()
        for item in records:
            writer.writerow({key: item[key] for key in writer.fieldnames})


def junit_records(paths: list[Path], output: Path) -> list[dict]:
    records: list[dict] = []
    for path in paths:
        try:
            tree = ET.parse(path)
            root = tree.getroot()
        except (ET.ParseError, OSError):
            records.append(
                {
                    "className": path.name,
                    "name": "invalid-junit",
                    "status": "ERROR",
                    "durationSeconds": 0,
                    "evidence": str(path.relative_to(output)),
                }
            )
            continue
        for parent in root.iter():
            for child in list(parent):
                if child.tag in {"properties", "system-out", "system-err"}:
                    parent.remove(child)
        for case in root.iter("testcase"):
            status = (
                "FAILED"
                if case.find("failure") is not None
                else "ERROR"
                if case.find("error") is not None
                else "SKIPPED"
                if case.find("skipped") is not None
                else "PASSED"
            )
            records.append(
                {
                    "className": case.get("classname", ""),
                    "name": case.get("name", ""),
                    "status": status,
                    "durationSeconds": float(case.get("time", "0")),
                    "evidence": str(path.relative_to(output)),
                }
            )
        for element in root.iter():
            if element.text:
                element.text = _safe_text(element.text)
            if element.tail:
                element.tail = _safe_text(element.tail)
            for key, value in element.attrib.items():
                element.set(key, _safe_text(value))
        path.write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
        try:
            ET.parse(path)
        except ET.ParseError:
            records.append(
                {
                    "className": path.name,
                    "name": "sanitized-junit-invalid",
                    "status": "ERROR",
                    "durationSeconds": 0,
                    "evidence": str(path.relative_to(output)),
                }
            )
    return records


def run_batch(
    name: str,
    command: list[str],
    output: Path,
    env: dict[str, str],
    timeout: int = 600,
) -> dict:
    started = time.monotonic()
    status = "ERROR"
    code = None
    text = ""
    try:
        process = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
        code = process.returncode
        status = "PASSED" if code == 0 else "FAILED"
        text = process.stdout.decode("utf-8", errors="replace")
    except subprocess.TimeoutExpired as error:
        text = (error.stdout or b"").decode("utf-8", errors="replace")
        text += "\n[STAGE10_TIMEOUT]"
    except OSError as error:
        text = type(error).__name__
    log = output / "logs" / f"{name}.log"
    log.write_text(_safe_text(text), encoding="utf-8")
    return {
        "id": name,
        "status": status,
        "exitCode": code,
        "command": _safe_command(command),
        "durationSeconds": round(time.monotonic() - started, 3),
        "logLocation": str(log.relative_to(output)),
    }


def source_fingerprint() -> str:
    digest = hashlib.sha256()
    for path in source_paths():
        digest.update(str(path.relative_to(ROOT)).encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def source_paths() -> list[Path]:
    paths = {
        DATASET_PATH,
        ROOT / "qa/fixtures/manifest.json",
        ROOT / "qa/scripts/stage10_metrics.py",
        ROOT / "qa/scripts/collect_stage10_python.py",
        Path(__file__),
        ROOT / "services/ai-retrieval-service/pyproject.toml",
        ROOT / "services/ai-retrieval-service/uv.lock",
        ROOT / "apps/trip-agent-api/pom.xml",
    }
    paths.update((ROOT / "qa/fixtures/documents").glob("*"))
    paths.update((ROOT / "qa/fixtures/retrieval").glob("*.json"))
    paths.update(
        (ROOT / "services/ai-retrieval-service/src/trip_agent_ai").rglob("*.py")
    )
    paths.update(
        path
        for path in (ROOT / "apps/trip-agent-api/src/main/java").rglob("*.java")
        if path.is_file()
    )
    paths.update(
        path
        for path in (ROOT / "apps/trip-agent-api/src/main/resources").rglob("*")
        if path.is_file()
    )
    paths.update(
        path
        for path in (ROOT / "apps/trip-agent-api/src/test/java").rglob("*.java")
        if path.stem in JAVA_TESTS
    )
    paths.update(
        path
        for path in (ROOT / "packages/api-contract").rglob("*")
        if path.is_file()
    )
    return sorted(paths)


def _report_metric(evaluation: dict | None, name: str) -> dict:
    value = _evaluation_metric(evaluation, name)
    if value is None:
        return not_measured("JAVA_EVALUATION_REPORT_MISSING")
    return {
        "status": "MEASURED",
        "value": round(value, 4),
        "numerator": None,
        "denominator": None,
        "provenance": "fresh-java-evaluation-report",
    }


def _observed_metric(
    value: float | None,
    numerator: int,
    denominator: int,
    missing_reason: str,
) -> dict:
    if value is None:
        return not_measured(missing_reason)
    return measured(numerator, denominator, value=value)


def _evaluation_metric(evaluation: dict | None, name: str) -> float | None:
    if evaluation is None:
        return None
    value = evaluation.get("metrics", {}).get(name)
    return float(value) if isinstance(value, (int, float)) else None


def _evaluation_number(evaluation: dict | None, name: str) -> int | None:
    value = _evaluation_metric(evaluation, name)
    return int(value) if value is not None else None


def _test_passed(junit: list[dict], class_name: str, method: str) -> bool:
    matching = [
        item
        for item in junit
        if class_name in item["className"] and item["name"].startswith(method)
    ]
    return len(matching) == 1 and matching[0]["status"] == "PASSED"


def _test_environment() -> dict[str, str]:
    env = dict(os.environ)
    env.update(
        {
            "APP_ENV": "test",
            "AI_SERVICE_TOKEN": "test-service-token",
            "NEO4J_PASSWORD": "test-neo4j-password",
            "DOCUMENT_PARSER_MODE": "mock",
            "RETRIEVAL_PROVIDER_MODE": "mock",
            "GRAPH_STORE_MODE": "memory",
            "DEEPSEEK_BASE_URL": "http://127.0.0.1:65535",
            "ALLOWED_EXTERNAL_HOSTS": "localhost,127.0.0.1",
            "OTEL_ENABLED": "false",
            "QA_RANDOM_SEED": "20260908",
            "PYTHONPATH": str(ROOT / "services/ai-retrieval-service/src"),
        }
    )
    return env


def _safe_text(text: str) -> str:
    cleaned = sanitize(text, secret_values())
    cleaned = cleaned.replace(str(ROOT), "[REPOSITORY_ROOT]")
    cleaned = cleaned.replace(str(Path.home()), "[HOME]")
    temporary = tempfile.gettempdir()
    cleaned = cleaned.replace(f"/private{temporary}", "[TEMP]")
    cleaned = cleaned.replace(temporary, "[TEMP]")
    for username in {getpass.getuser(), os.getenv("USER"), os.getenv("LOGNAME")}:
        if username:
            cleaned = re.sub(
                rf"(?<![A-Za-z0-9_.-]){re.escape(username)}(?![A-Za-z0-9_.-])",
                "[LOCAL_USER]",
                cleaned,
            )
    return cleaned


def _safe_command(command: list[str]) -> list[str]:
    return [_safe_text(part) for part in command]


def _validate_run_id(value: str, parser: argparse.ArgumentParser) -> None:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", value) or value in {
        ".",
        "..",
    }:
        parser.error("--run must be a safe directory name")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
