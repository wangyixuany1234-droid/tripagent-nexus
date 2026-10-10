#!/usr/bin/env python3
"""Enforce backend functional coverage by architectural layer."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jacoco", required=True)
    parser.add_argument("--python", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def matches(path: str, patterns: list[str]) -> bool:
    source_path = PurePosixPath(path)
    return any(source_path.match(pattern) for pattern in patterns)


def result(
    covered: int,
    missed: int,
    minimum: float,
    files: list[str],
) -> dict[str, Any]:
    total = covered + missed
    ratio = covered / total if total else 0.0
    return {
        "coveredLines": covered,
        "missedLines": missed,
        "totalLines": total,
        "ratio": round(ratio, 6),
        "percent": round(ratio * 100, 2),
        "minimum": minimum,
        "passed": total > 0 and ratio >= minimum,
        "files": sorted(files),
    }


def java_groups(
    report_path: Path,
    config: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    root = ET.parse(report_path).getroot()
    source_files: list[tuple[str, ET.Element]] = []
    for package in root.findall("package"):
        package_name = package.attrib["name"]
        for source in package.findall("sourcefile"):
            source_files.append((f"{package_name}/{source.attrib['name']}", source))

    groups: dict[str, dict[str, Any]] = {}
    for name, group_config in config.items():
        covered = 0
        missed = 0
        selected: list[str] = []
        for source_path, source in source_files:
            if not matches(source_path, group_config["includes"]):
                continue
            counter = source.find("counter[@type='LINE']")
            if counter is None:
                continue
            covered += int(counter.attrib["covered"])
            missed += int(counter.attrib["missed"])
            selected.append(source_path)
        groups[name] = result(
            covered,
            missed,
            float(group_config["minimum"]),
            selected,
        )
    return groups


def python_groups(
    report_path: Path,
    config: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    files = json.loads(report_path.read_text(encoding="utf-8"))["files"]
    groups: dict[str, dict[str, Any]] = {}
    for name, group_config in config.items():
        covered = 0
        missed = 0
        selected: list[str] = []
        for raw_path, details in files.items():
            source_path = raw_path.replace("\\", "/")
            if not matches(source_path, group_config["includes"]):
                continue
            summary = details["summary"]
            statements = int(summary["num_statements"])
            covered_lines = int(summary["covered_lines"])
            covered += covered_lines
            missed += statements - covered_lines
            selected.append(source_path)
        groups[name] = result(
            covered,
            missed,
            float(group_config["minimum"]),
            selected,
        )
    return groups


def main() -> None:
    args = parse_args()
    thresholds = json.loads(
        (REPOSITORY_ROOT / "qa/config/thresholds.json").read_text(encoding="utf-8")
    )["coverage"]["backendFunctional"]
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "java": java_groups(Path(args.jacoco), thresholds["java"]),
        "python": python_groups(Path(args.python), thresholds["python"]),
    }
    failures = [
        f"{runtime}.{name}"
        for runtime in ("java", "python")
        for name, group in report[runtime].items()
        if not group["passed"]
    ]
    report["passed"] = not failures
    report["failures"] = failures

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    for runtime in ("java", "python"):
        for name, group in report[runtime].items():
            print(
                f"{runtime}.{name}: {group['percent']:.2f}% "
                f"(minimum {group['minimum'] * 100:.2f}%)"
            )
    if failures:
        raise SystemExit("Coverage gates failed: " + ", ".join(failures))


if __name__ == "__main__":
    main()
