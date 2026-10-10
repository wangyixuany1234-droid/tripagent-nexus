#!/usr/bin/env python3
"""Summarize scoped fault evidence, retaining unsuccessful attempts and coverage gaps."""

from __future__ import annotations

import argparse
import html
import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def summarize(run: Path, previous_runs: list[Path] | None = None) -> dict:
    config = json.loads((ROOT / "qa/config/chaos-stage7.json").read_text())
    records: dict[str, list[dict]] = {}
    paths = {
        path
        for directory in [run, *(previous_runs or [])]
        for path in (directory / "experiments").glob("*.json")
    }
    for path in sorted(paths):
        record = json.loads(path.read_text())
        timings = {}
        for metric, start, end in (
            ("detectionMs", "injectedAt", "firstDetectedAt"),
            ("degradationMs", "injectedAt", "degradationStartedAt"),
            ("recoveryAfterClearMs", "faultClearedAt", "recoveredAt"),
        ):
            if record.get(start) and record.get(end):
                elapsed = datetime.fromisoformat(record[end]) - datetime.fromisoformat(
                    record[start]
                )
                timings[metric] = round(elapsed.total_seconds() * 1000)
        record["timings"] = timings
        record["evidenceFile"] = (
            str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)
        )
        records.setdefault(record["id"], []).append(record)
    scenarios = []
    for scenario in config["scenarios"]:
        attempts = sorted(
            records.get(scenario["id"], []), key=lambda item: item["finishedAt"]
        )
        by_mode = {item["injectionMode"]: item for item in attempts}
        latest = attempts[-1] if attempts else {"status": "NOT_EXECUTED"}
        if scenario["id"].startswith("mcp."):
            latest = by_mode.get("OFFICIAL_MCP_HTTP_STUB_PROVIDER", latest)
        failed = [item for item in by_mode.values() if item["status"] != "PASSED"]
        if failed:
            latest = failed[-1]
        scenarios.append(
            {
                **scenario,
                "latest": latest,
                "attemptCount": len(attempts),
                "evidenceByMode": list(by_mode.values()),
            }
        )
    junit = []
    for path in sorted((run / "junit").rglob("*.xml")):
        if not path.name.startswith("TEST-") and not path.name.startswith("python"):
            continue
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        junit.append(
            {
                "file": str(path),
                "tests": sum(int(item.get("tests", 0)) for item in suites),
                "failures": sum(int(item.get("failures", 0)) for item in suites),
                "errors": sum(int(item.get("errors", 0)) for item in suites),
                "skipped": sum(int(item.get("skipped", 0)) for item in suites),
            }
        )
    resume_ids = ("a2a.resume.ResearchAgent", "a2a.resume.AccommodationAgent")
    resume_results = [
        max(records[identifier], key=lambda item: item["finishedAt"])
        if records.get(identifier) else {"id": identifier, "status": "NOT_EXECUTED"}
        for identifier in resume_ids
    ]
    gaps = [
        "Trace 已验证任务关联及部分组件标记，尚未逐实验验证完整 Jaeger 导出和浏览器错误展示。",
        "已验证连接池、调用许可及 Kafka Lag 恢复，尚未在完整长稳负载下验证所有线程、HTTP 连接和内存回归基线。",
    ]
    if any(result["status"] != "PASSED" for result in resume_results):
        gaps.append("运行中 Agent 的串行及并行中间步骤恢复尚未全部通过。")
    if not any(
        item["status"] == "PASSED" for item in records.get("a2a.processRestart", [])
    ):
        gaps.append("尚未完成应用进程重启与 Kafka 业务积压恢复联动实验。")
    if any(
        not any(
            evidence["injectionMode"] == "OFFICIAL_MCP_HTTP_STUB_PROVIDER"
            and evidence["status"] == "PASSED"
            for evidence in item["evidenceByMode"]
        )
        for item in scenarios
        if item["id"].startswith("mcp.")
    ):
        gaps.append("MCP 官方 HTTP 传输故障未全部通过。")
    passed = sum(item["latest"]["status"] == "PASSED" for item in scenarios)
    known = {item["id"] for item in config["scenarios"]}
    additional_latest = [
        max(attempts, key=lambda item: item["finishedAt"])
        for name, attempts in records.items()
        if name not in known
    ]
    return {
        "stage": 7,
        "acceptanceStatus": "PARTIAL" if passed else "NOT_ACCEPTED",
        "totalScenarios": len(scenarios),
        "passedScopedExperiments": passed,
        "failedExperiments": sum(
            item["latest"]["status"] == "FAILED" for item in scenarios
        ),
        "notExecuted": sum(
            item["latest"]["status"] == "NOT_EXECUTED" for item in scenarios
        ),
        "acceptanceGaps": gaps,
        "agentResume": {
            "status": "PASSED" if all(item["status"] == "PASSED" for item in resume_results) else "NOT_ACCEPTED",
            "scope": "仅 AGENTS_RUNNING 且尚未写入候选计划；不覆盖任意非幂等外部副作用或计划写入中断。",
            "experiments": resume_results,
        },
        "additionalLatest": additional_latest,
        "additionalFailures": sum(
            item["status"] != "PASSED" for item in additional_latest
        ),
        "scenarios": scenarios,
        "additionalExperiments": [
            record
            for name, attempts in records.items()
            if name not in {item["id"] for item in config["scenarios"]}
            for record in attempts
        ],
        "junitRuns": junit,
        "junitCountingNote": "Reruns overlap; do not sum these rows as unique passing tests.",
        "previousRunDirectories": [str(path) for path in (previous_runs or [])],
    }


def write_reports(run: Path, summary: dict) -> None:
    output = run / "json/stage7-summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    rows = []
    for scenario in summary["scenarios"]:
        latest = scenario["latest"]
        rows.append(
            "<tr>"
            + "".join(
                f"<td>{html.escape(str(value))}</td>"
                for value in (
                    scenario["id"],
                    scenario["fault"],
                    latest["status"],
                    latest.get("injectionMode", ""),
                    latest.get("injectedAt", ""),
                    latest.get("firstDetectedAt", ""),
                    latest.get("degradationStartedAt", ""),
                    latest.get("recoveredAt", ""),
                    latest.get("lostTasks"),
                    latest.get("duplicateTasks"),
                    latest.get("traceIds", []),
                    latest.get("userVisibleImpact", ""),
                )
            )
            + "</tr>"
        )
    report = run / "html/stage7.html"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>阶段七故障实验</title>'
        "<style>body{font:14px system-ui;margin:24px;color:#222}table{border-collapse:collapse}"
        "td,th{padding:8px;border:1px solid #ddd;text-align:left}p{max-width:1000px}</style>"
        f"<h1>阶段七故障实验：部分验收</h1><p>已通过限定范围实验 {summary['passedScopedExperiments']}"
        f"/{summary['totalScenarios']}。null 表示未测量或不适用，不表示零。不同复测不能累计为唯一用例数。</p>"
        "<table><tr><th>场景</th><th>故障</th><th>实验结果</th><th>注入层级</th>"
        "<th>注入</th><th>检测</th><th>降级</th><th>恢复</th><th>丢失任务</th><th>重复任务</th>"
        "<th>traceId</th><th>用户影响</th></tr>" + "".join(rows) + "</table></html>"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--include-run", type=Path, action="append", default=[])
    args = parser.parse_args()
    summary = summarize(
        args.run.resolve(), [path.resolve() for path in args.include_run]
    )
    write_reports(args.run.resolve(), summary)
    print(
        json.dumps(
            {
                key: value
                for key, value in summary.items()
                if key
                in {
                    "acceptanceStatus",
                    "totalScenarios",
                    "passedScopedExperiments",
                    "failedExperiments",
                    "notExecuted",
                }
            },
            ensure_ascii=False,
        )
    )
    if (
        summary["failedExperiments"]
        or summary["notExecuted"]
        or summary["additionalFailures"]
    ):
        sys.exit(1)
    if args.require_complete and summary["acceptanceGaps"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
