#!/usr/bin/env python3
"""Enforce separate Python line and branch coverage thresholds."""

import argparse
import json
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    thresholds = json.loads(
        (REPOSITORY_ROOT / "qa/config/thresholds.json").read_text(encoding="utf-8")
    )["coverage"]["python"]
    totals = json.loads(Path(args.report).read_text(encoding="utf-8"))["totals"]
    line_percent = totals.get(
        "percent_statements_covered",
        totals["percent_covered"],
    )
    branch_percent = (
        100.0
        if totals["num_branches"] == 0
        else totals["covered_branches"] * 100.0 / totals["num_branches"]
    )
    failures = []
    if line_percent < thresholds["line"]:
        failures.append(f"行覆盖率 {line_percent:.2f}% 低于 {thresholds['line']:.2f}%")
    if branch_percent < thresholds["branch"]:
        failures.append(
            f"分支覆盖率 {branch_percent:.2f}% 低于 {thresholds['branch']:.2f}%"
        )
    if failures:
        raise SystemExit("；".join(failures))
    print(
        f"Python 覆盖率门禁通过：行 {line_percent:.2f}%，分支 {branch_percent:.2f}%。"
    )


if __name__ == "__main__":
    main()
