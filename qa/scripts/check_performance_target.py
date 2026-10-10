#!/usr/bin/env python3
"""Validate the runtime provider mode before a k6 process can start."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import urljoin
from urllib.request import Request, urlopen


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--expected-provider", choices=("MOCK", "LIVE"), required=True)
    parser.add_argument("--report", type=Path, required=True)
    return parser.parse_args()


def fail(message: str) -> None:
    print(f"性能测试目标门禁拒绝执行：{message}", file=sys.stderr)
    raise SystemExit(2)


def main() -> None:
    args = parse_args()
    health_url = urljoin(args.base_url.rstrip("/") + "/", "api/v1/health")
    request = Request(health_url)
    try:
        with urlopen(request, timeout=5) as response:
            status = response.status
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("目标健康检查失败")

    provider = payload.get("providerMode")
    healthy = status == 200 and payload.get("status") == "UP"
    if not healthy or provider != args.expected_provider:
        fail("目标未通过健康状态或 Provider 模式检查")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(
            {
                "status": "passed",
                "target": args.base_url,
                "providerMode": provider,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
